import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class RefreshingMarketMaker extends ReefBotV1 {
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
    if (activeOrders.length > 0) {
      const cancels = await Promise.all(
        activeOrders.map((order) => ctx.orders.safe.cancel({ orderId: order.orderId, instrumentId: order.instrumentId })),
      );
      return cancels.flatMap((cancel) => (cancel.ok ? [cancel.value] : [ctx.actions.noop(cancel.denial.message)]));
    }

    const snapshot = await ctx.marketData.snapshot(instrumentId);
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const orderSize = ctx.config.number("orderSize");
    const spread = ctx.config.number("spread");
    return [
      ctx.orders.placeLimit({
        instrumentId,
        side: "BUY",
        quantity: orderSize,
        limitPrice: snapshot.value.midPrice - spread / 2,
      }),
      ctx.orders.placeLimit({
        instrumentId,
        side: "SELL",
        quantity: orderSize,
        limitPrice: snapshot.value.midPrice + spread / 2,
      }),
    ];
  }
}
