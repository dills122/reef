import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../../src/index";

export default class SyncHangingBot extends ReefBotV1 {
  static override metadata = {
    name: "sync-hanging-bot",
    publisher: "Reef Test Fixtures",
    email: "fixtures@reef.local",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  } as const;

  override async onTick(_ctx: BotContextV1): Promise<readonly BotActionV1[]> {
    while (true) {
      // Intentionally hangs to prove the tester uses a killable hosted worker.
    }
  }
}
