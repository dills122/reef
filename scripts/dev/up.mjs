import { devUp } from "./lib/dev-stack.mjs";
import { loadDotEnv } from "./lib/dev-utils.mjs";

loadDotEnv();
await devUp();
