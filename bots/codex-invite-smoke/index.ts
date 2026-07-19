import { ReefBotV1, type BotActionV1, type BotContextV1 } from "@reef/bot-sdk";

/** Minimal deterministic bot used to exercise the invite-only submission path. */
export default class CodexInviteSmokeBot extends ReefBotV1 {
  static override metadata = {
    name: "codex-invite-smoke",
    publisher: "dills122",
    email: "15662762+dills122@users.noreply.github.com",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
    description: "Disposable smoke-test bot for the invite-only fork submission workflow.",
    tags: ["smoke", "invite-preview", "hosted-ci"],
  } as const;

  override async onTick(_ctx: BotContextV1): Promise<BotActionV1[]> {
    return [];
  }
}
