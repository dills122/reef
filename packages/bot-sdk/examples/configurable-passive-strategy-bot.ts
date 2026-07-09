import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class ConfigurablePassiveStrategyBot extends ReefBotV1 {
  static override metadata = {
    name: "configurable-passive-strategy-bot",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Example configurable passive strategy bot for local multi-instrument arena shakedowns.",
    tags: ["example", "strategy", "passive"],
  } as const;

  override async onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]> {
    const instrumentId = ctx.config.string("instrumentId");
    const side = ctx.config.optionalString("side") === "SELL" ? "SELL" : "BUY";
    const orderSize = ctx.config.number("orderSize");
    const priceOffset = ctx.config.optionalNumber("priceOffset") ?? 1;
    const snapshot = await ctx.marketData.snapshot(instrumentId);

    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const signedOffset = side === "BUY" ? -Math.abs(priceOffset) : Math.abs(priceOffset);
    return [
      ctx.orders.placeLimit({
        instrumentId,
        side,
        quantity: orderSize,
        limitPrice: Number((snapshot.value.midPrice + signedOffset).toFixed(2)),
      }),
    ];
  }
}
