const { ReefBotV1 } = __reefBotSdk;

module.exports.default = class HostedSimpleMarketMaker extends ReefBotV1 {
  static metadata = {
    name: "hosted-simple-market-maker",
    publisher: "Reef Examples",
    email: "bots@example.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Compiled hosted-runner artifact example.",
  };

  async onTick(ctx) {
    const snapshot = await ctx.marketData.snapshot("AAPL");
    if (!snapshot.ok) {
      return [ctx.actions.noop("snapshot unavailable")];
    }

    return [
      ctx.orders.placeLimit({
        instrumentId: "AAPL",
        side: "BUY",
        quantity: 1,
        limitPrice: snapshot.value.midPrice - 1,
      }),
    ];
  }
};
