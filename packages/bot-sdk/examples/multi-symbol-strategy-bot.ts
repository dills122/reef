import {
  ReefBotV1,
  type BotActionV1,
  type BotContextV1,
  type BotSignalV1,
  type BotStrategyEventV1,
  type BotStrategyV1,
  type HistoricalBarV1,
} from "../src/index";

class BollingerMomentumStrategy implements BotStrategyV1 {
  readonly id = "bollinger-momentum";
  readonly subscription = {
    instruments: ["AAPL", "MSFT", "NVDA", "TSLA"],
    bars: [
      { instrumentId: "AAPL", interval: "1m" as const, lookback: 20 },
      { instrumentId: "MSFT", interval: "1m" as const, lookback: 20 },
      { instrumentId: "NVDA", interval: "1m" as const, lookback: 20 },
      { instrumentId: "TSLA", interval: "1m" as const, lookback: 20 },
    ],
  };

  async onEvent(event: BotStrategyEventV1): Promise<readonly BotSignalV1[]> {
    if (event.type !== "bars_closed" || event.bars.length < 20) {
      return [];
    }

    const bars = event.bars.slice(-20);
    const closes = bars.map((bar) => bar.close);
    const lastBar = bars[bars.length - 1];
    const previousBar = bars[bars.length - 5] ?? bars[0];
    if (lastBar === undefined || previousBar === undefined) {
      return [];
    }

    const middle = average(closes);
    const deviation = standardDeviation(closes);
    const lower = middle - deviation * 2;
    const upper = middle + deviation * 2;
    const momentum = lastBar.close - previousBar.close;

    if ((lastBar.close < lower || lastBar.close < middle) && momentum > 0) {
      return [signal(this.id, event.instrumentId, "BUY", lastBar, "lower-band-reversal", Math.min(1, Math.abs(momentum) / deviation))];
    }
    if ((lastBar.close > upper || lastBar.close > middle) && momentum < 0) {
      return [signal(this.id, event.instrumentId, "SELL", lastBar, "upper-band-fade", Math.min(1, Math.abs(momentum) / deviation))];
    }
    return [];
  }
}

export default class MultiSymbolStrategyBot extends ReefBotV1 {
  static override metadata = {
    name: "multi-symbol-strategy",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.0.0",
    botApiVersion: "v1",
    description: "Example v1.5 strategy bot using Bollinger-style bands and momentum signals across several instruments.",
    tags: ["example", "strategy", "multi-symbol"],
  } as const;

  override readonly strategies = [new BollingerMomentumStrategy()] as const;

  override async onSignal(signalValue: BotSignalV1, ctx: BotContextV1): Promise<BotActionV1[]> {
    if (signalValue.confidence < 0.5) {
      return [ctx.actions.noop("signal confidence below threshold")];
    }

    return [
      ctx.orders.placeLimit({
        instrumentId: signalValue.instrumentId,
        side: signalValue.side,
        quantity: ctx.config.number("orderSize"),
        limitPrice: signalValue.referencePrice,
      }),
    ];
  }

  override async onTick(_ctx: BotContextV1): Promise<BotActionV1[]> {
    return [];
  }
}

function signal(
  strategyId: string,
  instrumentId: string,
  side: "BUY" | "SELL",
  bar: HistoricalBarV1,
  reason: string,
  confidence: number,
): BotSignalV1 {
  return {
    strategyId,
    instrumentId,
    side,
    confidence,
    referencePrice: bar.close,
    reason,
  };
}

function average(values: readonly number[]): number {
  return values.reduce((sum, value) => sum + value, 0) / values.length;
}

function standardDeviation(values: readonly number[]): number {
  const avg = average(values);
  return Math.sqrt(average(values.map((value) => (value - avg) ** 2)));
}
