import { ReefBotV1, type BotActionV1, type BotContextV1 } from "../../src/index";
import leftPad from "left-pad";

export default class UnapprovedPackageBot extends ReefBotV1 {
  static override metadata = {
    name: "unapproved-package-bot",
    publisher: "Reef Test Fixtures",
    email: "fixtures@reef.local",
    version: "1.0.0",
    sdkVersion: "1.5.0",
    botApiVersion: "v1",
  } as const;

  override async onTick(ctx: BotContextV1): Promise<readonly BotActionV1[]> {
    return [ctx.actions.noop(leftPad("blocked", 8))];
  }
}
