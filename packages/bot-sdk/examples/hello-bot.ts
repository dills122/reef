import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../src/index";

export default class HelloBot extends ReefBotV1 {
  static override metadata = {
    name: "hello-bot",
    publisher: "Reef Examples",
    email: "examples@reef.local",
    version: "1.0.0",
    sdkVersion: "1.0.0",
    botApiVersion: "v1",
    description: "Minimal no-op bot used to verify the v1 lifecycle.",
    tags: ["example", "noop"],
  } as const;

  override async onStart(ctx: BotContextV1): Promise<void> {
    ctx.log.info("hello-bot started", { tickIntervalMs: ctx.policy.tickIntervalMs });
  }

  override async onTick(ctx: BotContextV1): Promise<BotActionV1[]> {
    return [ctx.actions.noop("hello-bot waits for the next tick")];
  }

  override async onStop(ctx: BotContextV1): Promise<void> {
    ctx.log.info("hello-bot stopped");
  }
}

