import { env, loadDotEnv } from "./lib/dev-utils.mjs";
import { printStreamProfileSummary, streamProfileNames, validateStreamProfile } from "./lib/stream-profile-guard.mjs";

loadDotEnv();

const profile = process.argv[2] || env("DEV_STREAM_PROFILE", "stream-direct-nodb");
if (profile === "--help" || profile === "-h") {
  console.log(`usage: node scripts/dev/stream-profile-validate.mjs [${streamProfileNames.join("|")}]`);
  process.exit(0);
}

validateStreamProfile(profile);
printStreamProfileSummary(profile);
console.log(`stream profile ${profile} ok`);
