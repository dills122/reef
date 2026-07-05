import { env as readEnv, run as runCommand } from "./dev-utils.mjs";

export async function devUp(options = {}) {
  const context = createContext(options);
  configureComposeProfiles(context);

  await startPostgres(context);
  await runMigrations(context);
  await startFullStack(context);
}

export async function devReset(options = {}) {
  const context = createContext(options);
  configureComposeProfiles(context);

  context.log("stopping stack and removing volumes...");
  await context.run("docker", ["compose", "down", "--volumes", "--remove-orphans"]);

  context.log("starting postgres...");
  await startPostgres(context);
  await runMigrations(context);

  context.log("starting stack...");
  await startFullStack(context);

  if (context.runSmoke) {
    context.log(`running smoke verification (timeout=${context.smokeWaitTimeoutSeconds}s)...`);
    if (!context.processEnv.DEV_WAIT_TIMEOUT_SECONDS) {
      context.processEnv.DEV_WAIT_TIMEOUT_SECONDS = context.smokeWaitTimeoutSeconds;
    }
    await context.run(context.jsRuntime, ["scripts/dev/smoke.mjs"]);
  } else {
    context.log("skipping smoke verification (set DEV_RESET_RUN_SMOKE=1 to enable)");
  }
}

function createContext(options) {
  const env = options.env ?? readEnv;
  return {
    env,
    log: options.log ?? console.log,
    processEnv: options.processEnv ?? process.env,
    run: options.run ?? runCommand,
    jsRuntime: env("JS_RUNTIME", "bun"),
    profiles: env("DEV_COMPOSE_PROFILES", ""),
    buildImages: env("DEV_COMPOSE_BUILD", "1") !== "0",
    waitTimeoutSeconds: env("DEV_COMPOSE_WAIT_TIMEOUT_SECONDS", "300"),
    runSmoke: env("DEV_RESET_RUN_SMOKE", "0") === "1",
    smokeWaitTimeoutSeconds: env("DEV_RESET_SMOKE_WAIT_TIMEOUT_SECONDS", "45"),
  };
}

function configureComposeProfiles(context) {
  if (context.profiles) {
    context.processEnv.COMPOSE_PROFILES = context.profiles;
  }
}

async function startPostgres(context) {
  await context.run("docker", [
    "compose",
    "up",
    "-d",
    "--remove-orphans",
    "--wait",
    "--wait-timeout",
    context.waitTimeoutSeconds,
    "postgres",
    "boundary-postgres",
    "projection-postgres",
  ]);
}

async function runMigrations(context) {
  await context.run(context.jsRuntime, ["scripts/dev/db/migrate.mjs"]);
}

async function startFullStack(context) {
  const args = [
    "compose",
    "up",
    "-d",
    "--remove-orphans",
    "--wait",
    "--wait-timeout",
    context.waitTimeoutSeconds,
  ];
  if (context.buildImages) {
    args.splice(3, 0, "--build");
  }
  await context.run("docker", args);
}
