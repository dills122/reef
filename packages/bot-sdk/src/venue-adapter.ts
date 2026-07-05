import type { BotActionV1, BotResultV1, OwnOrderV1, ReefBotMetadataV1 } from "./index";

export interface BotVenueAdapterContextV1 {
  readonly scenarioId?: string;
  readonly runId: string;
  readonly runKind?: "scenario" | "live" | "stress" | string;
  readonly venueSessionId: string;
  readonly clientId?: string;
  readonly actorId: string;
  readonly participantId: string;
  readonly accountId: string;
  readonly botId: string;
  readonly botVersion: string;
  readonly correlationId: string;
  readonly occurredAt: string;
  readonly currency?: string;
  readonly commandIdPrefix: string;
  readonly traceIdPrefix: string;
  readonly idempotencyKeyPrefix: string;
  readonly startingSequence?: number;
  readonly metadata?: ReefBotMetadataV1;
}

export interface VenueCommandRequestV1 {
  readonly method: "POST";
  readonly route: "/api/v1/orders/submit" | "/api/v1/orders/modify" | "/api/v1/orders/cancel";
  readonly headers: Readonly<Record<string, string>>;
  readonly body: Readonly<Record<string, string>>;
}

export function toVenueCommandRequestsV1(
  actions: readonly BotActionV1[],
  context: BotVenueAdapterContextV1,
): BotResultV1<readonly VenueCommandRequestV1[]> {
  const requests: VenueCommandRequestV1[] = [];
  const startingSequence = context.startingSequence ?? 1;

  for (let index = 0; index < actions.length; index += 1) {
    const action = actions[index];
    if (action === undefined || action.type === "noop") {
      continue;
    }

    const sequence = startingSequence + index;
    const common = commonCommandFields(context, sequence);

    switch (action.type) {
      case "submit_limit": {
        const orderId = action.order.clientOrderId ?? `${context.commandIdPrefix}-order-${sequence}`;
        requests.push({
          method: "POST",
          route: "/api/v1/orders/submit",
          headers: commandHeaders(context, sequence),
          body: omitUndefinedStringValues({
            ...common,
            orderId,
            instrumentId: action.order.instrumentId,
            participantId: context.participantId,
            accountId: context.accountId,
            side: action.order.side,
            orderType: "LIMIT",
            quantityUnits: String(action.order.quantity),
            limitPrice: String(action.order.limitPrice),
            currency: context.currency ?? "USD",
            timeInForce: action.order.timeInForce ?? "DAY",
            clientOrderId: action.order.clientOrderId,
            botId: context.botId,
            botVersion: context.botVersion,
            runId: context.runId,
            venueSessionId: context.venueSessionId,
          }),
        });
        break;
      }
      case "modify_order": {
        if (action.order.quantity === undefined || action.order.limitPrice === undefined) {
          return {
            ok: false,
            denial: {
              code: "NOT_ALLOWED",
              message: "Venue API v1 modify requires both quantity and limitPrice.",
            },
          };
        }
        requests.push({
          method: "POST",
          route: "/api/v1/orders/modify",
          headers: commandHeaders(context, sequence),
          body: {
            ...common,
            orderId: action.order.orderId,
            instrumentId: action.order.instrumentId,
            quantityUnits: String(action.order.quantity),
            limitPrice: String(action.order.limitPrice),
            botId: context.botId,
            botVersion: context.botVersion,
            runId: context.runId,
            venueSessionId: context.venueSessionId,
          },
        });
        break;
      }
      case "cancel_order": {
        requests.push({
          method: "POST",
          route: "/api/v1/orders/cancel",
          headers: commandHeaders(context, sequence),
          body: {
            ...common,
            orderId: action.order.orderId,
            instrumentId: action.order.instrumentId,
            reason: "bot-request",
            botId: context.botId,
            botVersion: context.botVersion,
            runId: context.runId,
            venueSessionId: context.venueSessionId,
          },
        });
        break;
      }
      case "submit_market":
        return unsupportedAction("Venue API v1 does not support market orders yet.");
      case "cancel_all":
        return unsupportedAction("cancelAll must be expanded by the arena orchestrator before venue submission.");
    }
  }

  return { ok: true, value: requests };
}

export function expandCancelAllActionsV1(
  actions: readonly BotActionV1[],
  ownOrders: readonly OwnOrderV1[],
): readonly BotActionV1[] {
  const expanded: BotActionV1[] = [];
  for (const action of actions) {
    if (action.type !== "cancel_all") {
      expanded.push(action);
      continue;
    }

    for (const order of ownOrders) {
      if (action.instrumentId !== undefined && order.instrumentId !== action.instrumentId) {
        continue;
      }
      if ((order.status === "OPEN" || order.status === "PARTIALLY_FILLED") && order.remainingQuantity > 0) {
        expanded.push({
          type: "cancel_order",
          order: {
            orderId: order.orderId,
            instrumentId: order.instrumentId,
          },
        });
      }
    }
  }
  return expanded;
}

function commonCommandFields(
  context: BotVenueAdapterContextV1,
  sequence: number,
): Readonly<Record<string, string>> {
  return {
    commandId: `${context.commandIdPrefix}-${sequence}`,
    traceId: `${context.traceIdPrefix}-${sequence}`,
    correlationId: context.correlationId,
    actorId: context.actorId,
    occurredAt: context.occurredAt,
    ...(context.scenarioId === undefined ? {} : { scenarioId: context.scenarioId }),
    ...(context.runKind === undefined ? {} : { runKind: context.runKind }),
  };
}

function commandHeaders(
  context: BotVenueAdapterContextV1,
  sequence: number,
): Readonly<Record<string, string>> {
  return {
    "Content-Type": "application/json",
    "X-Client-Id": context.clientId ?? `bot:${context.botId}`,
    "Idempotency-Key": `${context.idempotencyKeyPrefix}-${sequence}`,
  };
}

function unsupportedAction(message: string): BotResultV1<readonly VenueCommandRequestV1[]> {
  return {
    ok: false,
    denial: {
      code: "NOT_ALLOWED",
      message,
    },
  };
}

function omitUndefinedStringValues(values: Record<string, string | undefined>): Readonly<Record<string, string>> {
  return Object.fromEntries(Object.entries(values).filter(([, value]) => value !== undefined)) as Readonly<Record<string, string>>;
}
