import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class RefreshingMarketMaker extends ReefBotV1 {
  private readonly firstObservedByOrderId = new Map<string, number>();

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
    const nowMs = ctx.clock.now().getTime();
    const ownOrders = await ctx.orders.current();
    if (!ownOrders.ok) {
      return [ctx.actions.noop("own orders unavailable")];
    }

    const activeOrders = ownOrders.value.filter(
      (order) =>
        order.instrumentId === instrumentId &&
        (order.status === "OPEN" || order.status === "PARTIALLY_FILLED") &&
        order.remainingQuantity > 0,
    );
    const activeOrderIds = new Set(activeOrders.map((order) => order.orderId));
    for (const orderId of Array.from(this.firstObservedByOrderId.keys())) {
      if (!activeOrderIds.has(orderId)) {
        this.firstObservedByOrderId.delete(orderId);
      }
    }
    for (const order of activeOrders) {
      if (!this.firstObservedByOrderId.has(order.orderId)) {
        this.firstObservedByOrderId.set(order.orderId, nowMs);
      }
    }

    const staleOrders = activeOrders.filter((order) => {
      const firstObservedMs = this.firstObservedByOrderId.get(order.orderId) ?? nowMs;
      return nowMs - firstObservedMs >= quoteTtlMs;
    });
    if (staleOrders.length > 0) {
      const cancels = await Promise.all(
        staleOrders.map((order) => ctx.orders.safe.cancel({ orderId: order.orderId, instrumentId: order.instrumentId })),
      );
      return cancels.flatMap((cancel) => (cancel.ok ? [cancel.value] : [ctx.actions.noop(cancel.denial.message)]));
    }
    if (activeOrders.some((order) => order.side === "BUY") && activeOrders.some((order) => order.side === "SELL")) {
      return [ctx.actions.noop("quotes healthy")];
    }

    const snapshot = await ctx.marketData.snapshot(instrumentId);
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const orderSize = ctx.config.number("orderSize");
    const spread = ctx.config.number("spread");
    const actions: BotActionV1[] = [];
    if (!activeOrders.some((order) => order.side === "BUY")) {
      actions.push(
        ctx.orders.placeLimit({
          instrumentId,
          side: "BUY",
          quantity: orderSize,
          limitPrice: snapshot.value.midPrice - spread / 2,
        }),
      );
    }
    if (!activeOrders.some((order) => order.side === "SELL")) {
      actions.push(
        ctx.orders.placeLimit({
          instrumentId,
          side: "SELL",
          quantity: orderSize,
          limitPrice: snapshot.value.midPrice + spread / 2,
        }),
      );
    }
    return actions;
  }
}
