import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class RefreshingMarketMaker extends ReefBotV1 {
  private readonly firstObservedByOrderId = new Map<string, number>();
  private readonly cancelPendingOrderIds = new Set<string>();
  private readonly recentlyCancelledUntilByOrderId = new Map<string, number>();
  private replacementHoldUntilMs = 0;

  static override metadata = {
    name: "refreshing-market-maker",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Lifecycle-aware market maker that cancels stale quotes before replacing them.",
    tags: ["example", "market-maker", "cancel-replace"],
  } as const;

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    const instrumentId = ctx.config.string("instrumentId");
    const quoteTtlMs = ctx.config.optionalNumber("quoteTtlMs") ?? 2000;
    const replacementCooldownMs = ctx.config.optionalNumber("replacementCooldownMs") ?? 1000;
    const cancelSuppressMs = ctx.config.optionalNumber("cancelSuppressMs") ?? 300000;
    const maxCancelsPerTick = Math.max(1, Math.floor(ctx.config.optionalNumber("maxCancelsPerTick") ?? 1));
    const nowMs = ctx.clock.now().getTime();
    const ownOrders = await ctx.orders.current();
    if (!ownOrders.ok) {
      return [ctx.actions.noop("own orders unavailable")];
    }

    for (const [orderId, suppressUntilMs] of Array.from(this.recentlyCancelledUntilByOrderId.entries())) {
      if (nowMs >= suppressUntilMs) {
        this.recentlyCancelledUntilByOrderId.delete(orderId);
      }
    }

    const activeOrders = ownOrders.value.filter(
      (order) =>
        order.instrumentId === instrumentId &&
        (order.status === "OPEN" || order.status === "PARTIALLY_FILLED") &&
        order.remainingQuantity > 0 &&
        !this.recentlyCancelledUntilByOrderId.has(order.orderId),
    );
    const quotableOrders = activeOrders.filter((order) => !this.cancelPendingOrderIds.has(order.orderId));
    const activeOrderIds = new Set(activeOrders.map((order) => order.orderId));
    for (const orderId of Array.from(this.firstObservedByOrderId.keys())) {
      if (!activeOrderIds.has(orderId)) {
        this.firstObservedByOrderId.delete(orderId);
        this.cancelPendingOrderIds.delete(orderId);
      }
    }
    for (const order of activeOrders) {
      if (!this.firstObservedByOrderId.has(order.orderId)) {
        this.firstObservedByOrderId.set(order.orderId, nowMs);
      }
    }

    const staleOrders = quotableOrders.filter((order) => {
      const firstObservedMs = this.firstObservedByOrderId.get(order.orderId) ?? nowMs;
      return nowMs - firstObservedMs >= quoteTtlMs;
    });
    if (staleOrders.length > 0) {
      const staleOrdersToCancel = staleOrders.slice(0, maxCancelsPerTick);
      const cancels = await Promise.all(
        staleOrdersToCancel.map((order) => ctx.orders.safe.cancel({ orderId: order.orderId, instrumentId: order.instrumentId })),
      );
      for (let index = 0; index < staleOrdersToCancel.length; index += 1) {
        const staleOrder = staleOrdersToCancel[index];
        if (staleOrder !== undefined && cancels[index]?.ok) {
          this.cancelPendingOrderIds.add(staleOrder.orderId);
          this.recentlyCancelledUntilByOrderId.set(staleOrder.orderId, nowMs + cancelSuppressMs);
        }
      }
      return cancels.flatMap((cancel) => (cancel.ok ? [cancel.value] : [ctx.actions.noop(cancel.denial.message)]));
    }
    if (quotableOrders.some((order) => order.side === "BUY") && quotableOrders.some((order) => order.side === "SELL")) {
      this.replacementHoldUntilMs = 0;
      return [ctx.actions.noop("quotes healthy")];
    }
    if (nowMs < this.replacementHoldUntilMs) {
      return [ctx.actions.noop("replacement pending")];
    }

    const snapshot = await ctx.marketData.snapshot(instrumentId);
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const orderSize = profileOrderSize(ctx);
    const spread = profileSpread(ctx, snapshot.value.midPrice);
    const actions: BotActionV1[] = [];
    if (!quotableOrders.some((order) => order.side === "BUY")) {
      actions.push(
        ctx.orders.placeLimit({
          instrumentId,
          side: "BUY",
          quantity: orderSize,
          limitPrice: snapshot.value.midPrice - spread / 2,
        }),
      );
    }
    if (!quotableOrders.some((order) => order.side === "SELL")) {
      actions.push(
        ctx.orders.placeLimit({
          instrumentId,
          side: "SELL",
          quantity: orderSize,
          limitPrice: snapshot.value.midPrice + spread / 2,
        }),
      );
    }
    if (actions.length > 0) {
      this.replacementHoldUntilMs = nowMs + replacementCooldownMs;
    }
    return actions;
  }
}

function profileOrderSize(ctx: BotContextV1): number {
  return Math.max(1, Math.floor(ctx.config.optionalNumber("actorProfile.quoteSize") ?? ctx.config.number("orderSize")));
}

function profileSpread(ctx: BotContextV1, midPrice: number): number {
  const quoteSpreadBps = ctx.config.optionalNumber("actorProfile.quoteSpreadBps");
  if (quoteSpreadBps !== undefined) {
    return Math.max(0.01, midPrice * Math.max(0, quoteSpreadBps) / 10_000);
  }
  return ctx.config.number("spread");
}
