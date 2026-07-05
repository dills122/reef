import {
  ReefBotV1,
  type BotActionV1,
  type BotContextV1,
  type BotSignalV1,
  type BotStrategyEventV1,
  type BotStrategyV1,
} from "../src/index";
import { BollingerBands, RSI, SMA } from "trading-signals";

export default class TechnicalIndicatorStrategyBot extends ReefBotV1 {
  static override metadata = {
    name: "technical-indicator-strategy-bot",
    publisher: "Reef Examples",
    email: "bots@example.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Example strategy bot using an approved bundled technical-indicator package.",
    tags: ["example", "strategy", "technical-indicators"],
  } as const;

  override readonly strategies: readonly BotStrategyV1[] = [
    {
      id: "bb-rsi-reversion",
      subscription: {
        instruments: ["AAPL"],
        bars: [{ instrumentId: "AAPL", interval: "1m", lookback: 20 }],
      },
      async onEvent(event: BotStrategyEventV1): Promise<readonly BotSignalV1[]> {
        if (event.type !== "bars_closed" || event.bars.length < 20) {
          return [];
        }

        const bollinger = new BollingerBands(19, 2);
        const rsi = new RSI(14);
        const sma = new SMA(5);
        let latestBands: { lower: number; middle: number; upper: number } | null = null;
        let latestRsi: number | null = null;
        let latestSma: number | null = null;

        for (const bar of event.bars) {
          latestBands = bollinger.add(bar.close);
          latestRsi = rsi.add(bar.close);
          latestSma = sma.add(bar.close);
        }

        const latestBar = event.bars[event.bars.length - 1];
        if (latestBar === undefined || latestBands === null || latestRsi === null || latestSma === null) {
          return [];
        }

        if (latestBar.close < latestBands.middle && latestRsi < 50 && latestBar.close > latestSma) {
          return [
            {
              strategyId: "bb-rsi-reversion",
              instrumentId: event.instrumentId,
              side: "BUY",
              confidence: 0.66,
              referencePrice: latestBar.close,
              reason: "Close is below Bollinger middle band while RSI shows recoverable weakness.",
              metadata: {
                rsi: Number(latestRsi.toFixed(2)),
                sma: Number(latestSma.toFixed(2)),
                bandMiddle: Number(latestBands.middle.toFixed(2)),
              },
            },
          ];
        }

        return [];
      },
    },
  ];

  override async onTick(_ctx: BotContextV1): Promise<readonly BotActionV1[]> {
    return [];
  }

  override async onSignal(signal: BotSignalV1, ctx: BotContextV1): Promise<readonly BotActionV1[]> {
    if (signal.side !== "BUY") {
      return [ctx.actions.noop("technical indicator strategy only buys in this example")];
    }

    return [
      ctx.orders.placeLimit({
        instrumentId: signal.instrumentId,
        side: "BUY",
        quantity: 5,
        limitPrice: Number((signal.referencePrice * 0.999).toFixed(2)),
        timeInForce: "DAY",
      }),
    ];
  }
}
