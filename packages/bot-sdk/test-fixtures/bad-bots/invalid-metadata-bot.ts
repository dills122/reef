import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../../src/index";

export default class InvalidMetadataBot extends ReefBotV1 {
  static override metadata = {
    name: "Invalid Metadata Bot",
    publisher: "Reef Test Fixtures",
    email: "not-an-email",
    version: "one",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  } as const;

  override async onTick(_ctx: BotContextV1): Promise<BotActionV1[]> {
    return [];
  }
}
