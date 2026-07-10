import { ReefBotV1, type BotActionV1, type BotContextV1 } from "@reef/bot-sdk";

export default class DsteeleSmokeBot extends ReefBotV1 {
  static override metadata = {
    name: "dsteele-smoke-bot",
    publisher: "dills122",
    email: "15662762+dills122@users.noreply.github.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Conservative smoke-test market maker for the hosted bot submission flow.",
    tags: ["smoke", "market-maker", "hosted-ci"],
  } as const;

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    const instrumentId = ctx.config.optionalString("instrumentId") ?? "AAPL";
    const orderSize = ctx.config.optionalNumber("orderSize") ?? 5;
    const spread = ctx.config.optionalNumber("spread") ?? 1;

    const openOrders = await ctx.orders.current();
    if (!openOrders.ok) {
      return [ctx.actions.noop("own orders unavailable")];
    }

    const activeQuotes = openOrders.value.filter(
      (order) =>
        order.instrumentId === instrumentId &&
        (order.status === "OPEN" || order.status === "PARTIALLY_FILLED") &&
        order.remainingQuantity > 0,
    );
    if (activeQuotes.length >= 2) {
      return [ctx.actions.noop("quotes already resting")];
    }

    const snapshot = await ctx.marketData.snapshot(instrumentId);
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

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
