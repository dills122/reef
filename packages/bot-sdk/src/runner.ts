import {
  type BotActionV1,
  type BotDenialV1,
  type BotReadClientsV1,
  type BotRuntimePolicyV1,
  type HistoricalBarV1,
  type MarketSnapshotV1,
  type OwnOrderV1,
} from "./index";
import {
  createFixtureBotContextV1,
  defaultBotRuntimePolicyV1,
  type BotFixtureDataV1,
  type BotLogEntryV1,
  type ReefBotV1Constructor,
} from "./harness";
import {
  expandCancelAllActionsV1,
  toVenueCommandRequestsV1,
  type BotVenueAdapterContextV1,
  type VenueCommandRequestV1,
} from "./venue-adapter";
import {
  sendVenueCommandRequestsV1,
  type VenueCommandResponseV1,
  type VenueCommandTransportV1,
} from "./venue-client";

export interface BotScenarioFixtureV1 {
  readonly scenarioId: string;
  readonly runId: string;
  readonly runKind?: "scenario" | "live" | "stress" | string;
  readonly venueSessionId: string;
  readonly actorId: string;
  readonly participantId: string;
  readonly accountId: string;
  readonly botId: string;
  readonly botVersion: string;
  readonly clientId?: string;
  readonly correlationId: string;
  readonly config: Record<string, string | number | boolean>;
  readonly policy?: Partial<BotRuntimePolicyV1>;
  readonly ticks: readonly BotScenarioTickV1[];
  readonly historicalBars?: Record<string, readonly HistoricalBarV1[]>;
  readonly initialOrders?: readonly OwnOrderV1[];
}

export interface BotScenarioTickV1 {
  readonly occurredAt: string;
  readonly marketSnapshots: Record<string, MarketSnapshotV1>;
  readonly historicalBarsClosed?: Record<string, readonly HistoricalBarV1[]>;
}

export interface BotScenarioRunOptionsV1 {
  readonly BotClass: ReefBotV1Constructor;
  readonly fixture: BotScenarioFixtureV1;
  readonly venueTransport?: VenueCommandTransportV1;
  readonly readClients?: BotReadClientsV1;
}

export interface BotScenarioRunReportV1 {
  readonly status: "completed" | "do_not_merge";
  readonly scenarioId: string;
  readonly runId: string;
  readonly ticksRun: number;
  readonly actionsProposed: number;
  readonly orderActionsProposed: number;
  readonly dataCalls: number;
  readonly issues: readonly BotScenarioIssueV1[];
  readonly denials: readonly BotDenialV1[];
  readonly logs: readonly BotLogEntryV1[];
  readonly ticks: readonly BotScenarioTickReportV1[];
  readonly finalOrders: readonly OwnOrderV1[];
}

export interface BotScenarioIssueV1 {
  readonly code: string;
  readonly message: string;
  readonly tick?: number;
}

export interface BotScenarioTickReportV1 {
  readonly tick: number;
  readonly occurredAt: string;
  readonly actions: readonly BotActionV1[];
  readonly venueCommands: readonly VenueCommandRequestV1[];
  readonly venueResponses: readonly VenueCommandResponseV1[];
  readonly denials: readonly BotDenialV1[];
  readonly dataCalls: number;
  readonly ordersAfterTick: readonly OwnOrderV1[];
}

export async function runBotScenarioV1(options: BotScenarioRunOptionsV1): Promise<BotScenarioRunReportV1> {
  const policy: BotRuntimePolicyV1 = { ...defaultBotRuntimePolicyV1, ...options.fixture.policy };
  const bot = new options.BotClass();
  const logs: BotLogEntryV1[] = [];
  const denials: BotDenialV1[] = [];
  const issues: BotScenarioIssueV1[] = [];
  const tickReports: BotScenarioTickReportV1[] = [];
  const orderState = new Map<string, OwnOrderV1>();
  const orderHistory = new Map<string, OwnOrderV1>();
  const tradeCommandWindows = new Map<number, number>();
  let actionsProposed = 0;
  let orderActionsProposed = 0;
  let dataCalls = 0;
  let commandSequence = 1;

  for (const order of options.fixture.initialOrders ?? []) {
    orderState.set(order.orderId, order);
    orderHistory.set(order.orderId, order);
  }
  await refreshOrdersFromReadClient(options.readClients, orderState, orderHistory, denials, issues);

  const startContext = createFixtureBotContextV1({
    policy,
    fixtureData: fixtureDataForState(options.fixture, orderState, orderHistory),
    readClients: options.readClients,
    logs,
    denials,
    counters: { dataCalls: 0, dataCallsThisTick: 0 },
  });
  await bot.onStart(startContext);

  for (let tick = 0; tick < options.fixture.ticks.length; tick += 1) {
    const fixtureTick = options.fixture.ticks[tick];
    if (fixtureTick === undefined) {
      continue;
    }
    await refreshOrdersFromReadClient(options.readClients, orderState, orderHistory, denials, issues, tick);

    const counters = { dataCalls: 0, dataCallsThisTick: 0 };
    const tickDenialsStart = denials.length;
    const context = createFixtureBotContextV1({
      policy,
      fixtureData: fixtureDataForState(options.fixture, orderState, orderHistory, fixtureTick.marketSnapshots),
      readClients: options.readClients,
      logs,
      denials,
      counters,
    });

    const actions = Array.from(await bot.onTick(context));
    const expandedActions = Array.from(expandCancelAllActionsV1(actions, Array.from(orderState.values())));
    const orderActionCount = expandedActions.filter(isOrderAction).length;
    let tickBlocked = false;
    actionsProposed += actions.length;
    orderActionsProposed += orderActionCount;
    dataCalls += counters.dataCalls;

    if (orderActionCount > policy.maxOrderActionsPerTick) {
      tickBlocked = true;
      issues.push({
        code: "max_order_actions_per_tick_exceeded",
        message: `Tick ${tick} proposed ${orderActionCount} order actions; limit is ${policy.maxOrderActionsPerTick}.`,
        tick,
      });
    }

    const secondWindow = Math.floor((tick * policy.tickIntervalMs) / 1000);
    const updatedWindowCount = (tradeCommandWindows.get(secondWindow) ?? 0) + orderActionCount;
    tradeCommandWindows.set(secondWindow, updatedWindowCount);
    if (updatedWindowCount > policy.maxTradeCommandsPerSecond) {
      tickBlocked = true;
      issues.push({
        code: "max_trade_commands_per_second_exceeded",
        message: `Second ${secondWindow} proposed ${updatedWindowCount} trade commands; limit is ${policy.maxTradeCommandsPerSecond}.`,
        tick,
      });
    }

    const venueResult = tickBlocked
      ? { ok: true as const, value: [] }
      : toVenueCommandRequestsV1(expandedActions, venueContext(options.fixture, fixtureTick.occurredAt, commandSequence));
    const venueCommands = venueResult.ok ? Array.from(venueResult.value) : [];
    let venueResponses: readonly VenueCommandResponseV1[] = [];
    let venueAccepted = venueResult.ok && !tickBlocked;
    if (!venueResult.ok) {
      venueAccepted = false;
      denials.push(venueResult.denial);
      issues.push({
        code: "venue_adapter_denial",
        message: venueResult.denial.message,
        tick,
      });
    } else if (options.venueTransport !== undefined && venueCommands.length > 0) {
      const sendResult = await sendVenueCommandRequestsV1(venueCommands, options.venueTransport);
      if (sendResult.ok) {
        venueResponses = sendResult.value;
      } else {
        venueAccepted = false;
        denials.push(sendResult.denial);
        issues.push({
          code: "venue_send_denial",
          message: sendResult.denial.message,
          tick,
        });
      }
    }

    if (venueAccepted && options.readClients?.orders === undefined) {
      applyActionsToOrderState(expandedActions, orderState, orderHistory, tick);
      commandSequence += expandedActions.filter((action) => action.type !== "noop").length;
    } else if (venueAccepted) {
      commandSequence += expandedActions.filter((action) => action.type !== "noop").length;
    }

    tickReports.push({
      tick,
      occurredAt: fixtureTick.occurredAt,
      actions: expandedActions,
      venueCommands,
      venueResponses,
      denials: denials.slice(tickDenialsStart),
      dataCalls: counters.dataCalls,
      ordersAfterTick: Array.from(orderState.values()),
    });
  }

  const stopContext = createFixtureBotContextV1({
    policy,
    fixtureData: fixtureDataForState(options.fixture, orderState, orderHistory),
    readClients: options.readClients,
    logs,
    denials,
    counters: { dataCalls: 0, dataCallsThisTick: 0 },
  });
  await bot.onStop(stopContext);

  return {
    status: issues.length > 0 ? "do_not_merge" : "completed",
    scenarioId: options.fixture.scenarioId,
    runId: options.fixture.runId,
    ticksRun: tickReports.length,
    actionsProposed,
    orderActionsProposed,
    dataCalls,
    issues,
    denials,
    logs,
    ticks: tickReports,
    finalOrders: Array.from(orderState.values()),
  };
}

async function refreshOrdersFromReadClient(
  readClients: BotReadClientsV1 | undefined,
  orderState: Map<string, OwnOrderV1>,
  orderHistory: Map<string, OwnOrderV1>,
  denials: BotDenialV1[],
  issues: BotScenarioIssueV1[],
  tick?: number,
): Promise<void> {
  if (readClients?.orders === undefined) {
    return;
  }
  const current = await readClients.orders.current();
  if (!current.ok) {
    denials.push(current.denial);
    issues.push({
      code: "live_order_read_denial",
      message: current.denial.message,
      ...(tick === undefined ? {} : { tick }),
    });
    return;
  }
  orderState.clear();
  for (const order of current.value) {
    orderState.set(order.orderId, order);
    orderHistory.set(order.orderId, order);
  }
}

function fixtureDataForState(
  fixture: BotScenarioFixtureV1,
  orderState: Map<string, OwnOrderV1>,
  orderHistory: Map<string, OwnOrderV1>,
  marketSnapshots?: Record<string, MarketSnapshotV1>,
): BotFixtureDataV1 {
  return {
    config: fixture.config,
    currentOrders: Array.from(orderState.values()),
    orderHistory: Array.from(orderHistory.values()),
    ...(marketSnapshots === undefined ? {} : { marketSnapshots }),
    ...(fixture.historicalBars === undefined ? {} : { historicalBars: fixture.historicalBars }),
  };
}

function venueContext(
  fixture: BotScenarioFixtureV1,
  occurredAt: string,
  startingSequence: number,
): BotVenueAdapterContextV1 {
  return {
    scenarioId: fixture.scenarioId,
    runId: fixture.runId,
    ...(fixture.runKind === undefined ? {} : { runKind: fixture.runKind }),
    venueSessionId: fixture.venueSessionId,
    actorId: fixture.actorId,
    participantId: fixture.participantId,
    accountId: fixture.accountId,
    ...(fixture.clientId === undefined ? {} : { clientId: fixture.clientId }),
    botId: fixture.botId,
    botVersion: fixture.botVersion,
    correlationId: fixture.correlationId,
    occurredAt,
    commandIdPrefix: `cmd-${fixture.botId}`,
    traceIdPrefix: `trace-${fixture.botId}`,
    idempotencyKeyPrefix: `idem-${fixture.botId}`,
    startingSequence,
  };
}

function applyActionsToOrderState(
  actions: readonly BotActionV1[],
  orderState: Map<string, OwnOrderV1>,
  orderHistory: Map<string, OwnOrderV1>,
  tick: number,
): void {
  for (let index = 0; index < actions.length; index += 1) {
    const action = actions[index];
    if (action === undefined) {
      continue;
    }

    switch (action.type) {
      case "submit_limit": {
        const orderId = action.order.clientOrderId ?? `fixture-order-${tick + 1}-${index + 1}`;
        const order: OwnOrderV1 = {
          orderId,
          instrumentId: action.order.instrumentId,
          side: action.order.side,
          quantity: action.order.quantity,
          remainingQuantity: action.order.quantity,
          limitPrice: action.order.limitPrice,
          status: "OPEN",
        };
        orderState.set(orderId, order);
        orderHistory.set(orderId, order);
        break;
      }
      case "modify_order": {
        const current = orderState.get(action.order.orderId);
        if (current !== undefined) {
          const nextQuantity = action.order.quantity ?? current.quantity;
          const updated: OwnOrderV1 = {
            ...current,
            quantity: nextQuantity,
            remainingQuantity: Math.min(current.remainingQuantity, nextQuantity),
            ...(action.order.limitPrice === undefined ? {} : { limitPrice: action.order.limitPrice }),
          };
          orderState.set(updated.orderId, updated);
          orderHistory.set(updated.orderId, updated);
        }
        break;
      }
      case "cancel_order": {
        const current = orderState.get(action.order.orderId);
        if (current !== undefined) {
          const canceled: OwnOrderV1 = {
            ...current,
            remainingQuantity: 0,
            status: "CANCELED",
          };
          orderState.delete(action.order.orderId);
          orderHistory.set(action.order.orderId, canceled);
        }
        break;
      }
      case "cancel_all": {
        for (const current of Array.from(orderState.values())) {
          if (action.instrumentId === undefined || current.instrumentId === action.instrumentId) {
            const canceled: OwnOrderV1 = { ...current, remainingQuantity: 0, status: "CANCELED" };
            orderState.delete(current.orderId);
            orderHistory.set(current.orderId, canceled);
          }
        }
        break;
      }
      case "submit_market":
      case "noop":
        break;
    }
  }
}

function isOrderAction(action: BotActionV1): boolean {
  return action.type !== "noop";
}
