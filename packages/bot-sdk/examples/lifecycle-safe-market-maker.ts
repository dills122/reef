import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class LifecycleSafeMarketMaker extends ReefBotV1 {
  static override metadata = {
    name: "lifecycle-safe-market-maker",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Example market maker that checks own orders before proposing new quotes.",
    tags: ["example", "market-maker", "lifecycle-safe"],
  } as const;

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    const instrumentId = ctx.config.string("instrumentId");
    const openOrders = await ctx.orders.current();
    if (!openOrders.ok) {
      return [ctx.actions.noop("own orders unavailable")];
    }

    const activeForInstrument = openOrders.value.filter(
      (order) =>
        order.instrumentId === instrumentId &&
        (order.status === "OPEN" || order.status === "PARTIALLY_FILLED") &&
        order.remainingQuantity > 0,
    );

    if (activeForInstrument.length >= 2) {
      return [ctx.actions.noop("quotes already resting")];
    }

    const snapshot = await ctx.marketData.snapshot(instrumentId);
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const orderSize = profileOrderSize(ctx);
    const spread = profileSpread(ctx, snapshot.value.midPrice);

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
