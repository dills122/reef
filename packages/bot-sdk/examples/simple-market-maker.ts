import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class SimpleMarketMaker extends ReefBotV1 {
  static override metadata = {
    name: "simple-market-maker",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.0.0",
    botApiVersion: "v1",
    description: "Example tick bot that proposes one bid and one offer around a public snapshot.",
    tags: ["example", "market-maker"],
  } as const;

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    const instrumentId = ctx.config.string("instrumentId");
    const orderSize = ctx.config.number("orderSize");
    const spread = ctx.config.number("spread");
    const snapshot = await ctx.marketData.snapshot(instrumentId);

    if (!snapshot.ok) {
      ctx.log.warn("market snapshot denied", { code: snapshot.denial.code });
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const bid = snapshot.value.midPrice - spread / 2;
    const ask = snapshot.value.midPrice + spread / 2;

    return [
      ctx.orders.placeLimit({
        instrumentId,
        side: "BUY",
        quantity: orderSize,
        limitPrice: bid,
      }),
      ctx.orders.placeLimit({
        instrumentId,
        side: "SELL",
        quantity: orderSize,
        limitPrice: ask,
      }),
    ];
  }
}
