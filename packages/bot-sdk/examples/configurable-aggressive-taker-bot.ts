import { ReefBotV1, type BotActionV1, type BotContextV1, type OrderSideV1 } from "../src/index";

export default class ConfigurableAggressiveTakerBot extends ReefBotV1 {
  private tickIndex = 0;

  static override metadata = {
    name: "configurable-aggressive-taker-bot",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Example configurable aggressive limit taker for local arena fill-pressure shakedowns.",
    tags: ["example", "strategy", "aggressive", "taker"],
  } as const;

  override async onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]> {
    const instrumentId = ctx.config.string("instrumentId");
    const orderSize = ctx.config.number("orderSize");
    const crossOffset = ctx.config.optionalNumber("crossOffset") ?? 0.01;
    const configuredSide = ctx.config.optionalString("side") ?? "ALTERNATE";
    const startAfterTicks = Math.max(0, Math.floor(ctx.config.optionalNumber("startAfterTicks") ?? 0));
    if (this.tickIndex < startAfterTicks) {
      this.tickIndex += 1;
      return [ctx.actions.noop("waiting for liquidity warmup")];
    }

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
      return [ctx.actions.noop("taker order already active")];
    }

    const snapshot = await ctx.marketData.snapshot(instrumentId);
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    const side = this.sideForTick(configuredSide);
    this.tickIndex += 1;
    const referencePrice = side === "BUY"
      ? snapshot.value.askPrice ?? snapshot.value.midPrice
      : snapshot.value.bidPrice ?? snapshot.value.midPrice;
    const signedOffset = side === "BUY" ? Math.abs(crossOffset) : -Math.abs(crossOffset);
    return [
      ctx.orders.placeLimit({
        instrumentId,
        side,
        quantity: orderSize,
        limitPrice: Number((referencePrice + signedOffset).toFixed(2)),
        timeInForce: "IOC",
      }),
    ];
  }

  private sideForTick(configuredSide: string): OrderSideV1 {
    if (configuredSide === "BUY" || configuredSide === "SELL") {
      return configuredSide;
    }
    return this.tickIndex % 2 === 0 ? "BUY" : "SELL";
  }
}
