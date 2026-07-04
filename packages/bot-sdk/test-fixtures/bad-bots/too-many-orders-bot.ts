import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../../src/index";

export default class TooManyOrdersBot extends ReefBotV1 {
  static override metadata = {
    name: "too-many-orders-bot",
    publisher: "Reef Test Fixtures",
    email: "fixtures@reef.local",
    version: "1.0.0",
    sdkVersion: "1.0.0",
    botApiVersion: "v1",
  } as const;

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    return Array.from({ length: 11 }, (_, index) =>
      ctx.orders.placeLimit({
        instrumentId: "AAPL",
        side: index % 2 === 0 ? "BUY" : "SELL",
        quantity: 1,
        limitPrice: 100 + index,
      }),
    );
  }
}
