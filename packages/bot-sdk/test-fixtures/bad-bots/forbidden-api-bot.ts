import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../../src/index";

export default class ForbiddenApiBot extends ReefBotV1 {
  static override metadata = {
    name: "forbidden-api-bot",
    publisher: "Reef Test Fixtures",
    email: "fixtures@reef.local",
    version: "1.0.0",
    sdkVersion: "1.0.0",
    botApiVersion: "v1",
  } as const;

  override async onTick(_ctx: BotContextV1): Promise<BotActionV1[]> {
    setTimeout(() => undefined, 1);
    return [];
  }
}
