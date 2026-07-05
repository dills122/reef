export type BotRuntimeConfigValueV1 = string | number | boolean;
export type BotRuntimeConfigValueTypeV1 = "string" | "number" | "boolean";
export type BotRuntimeConfigProviderV1 = "OpenBao";

export interface BotRuntimeConfigDescriptorV1 {
  readonly key: string;
  readonly provider: BotRuntimeConfigProviderV1;
  readonly secretPath: string;
  readonly required: boolean;
  readonly valueType?: BotRuntimeConfigValueTypeV1;
  readonly description?: string;
}

export interface BotRuntimeSecretProviderV1 {
  readonly provider: BotRuntimeConfigProviderV1;
  readSecret(path: string): Promise<BotRuntimeConfigSecretV1 | undefined>;
}

export type BotRuntimeConfigSecretV1 =
  | BotRuntimeConfigValueV1
  | Readonly<Record<string, BotRuntimeConfigValueV1 | undefined>>;

export interface ResolvedBotRuntimeConfigV1 {
  readonly values: Readonly<Record<string, BotRuntimeConfigValueV1>>;
}

export async function resolveBotRuntimeConfigV1(
  descriptors: readonly BotRuntimeConfigDescriptorV1[],
  secretProvider: BotRuntimeSecretProviderV1,
): Promise<ResolvedBotRuntimeConfigV1> {
  const values: Record<string, BotRuntimeConfigValueV1> = {};
  const seenKeys = new Set<string>();

  for (const descriptor of descriptors) {
    validateDescriptor(descriptor, secretProvider);
    if (seenKeys.has(descriptor.key)) {
      throw new Error(`Duplicate bot runtime config key ${descriptor.key}.`);
    }
    seenKeys.add(descriptor.key);

    const secret = await secretProvider.readSecret(descriptor.secretPath);
    const value = valueFromSecret(descriptor, secret);
    if (value === undefined) {
      if (descriptor.required) {
        throw new Error(`Missing required bot runtime config ${descriptor.key}.`);
      }
      continue;
    }
    values[descriptor.key] = assertValueType(descriptor, value);
  }

  return { values: Object.freeze({ ...values }) };
}

function validateDescriptor(
  descriptor: BotRuntimeConfigDescriptorV1,
  secretProvider: BotRuntimeSecretProviderV1,
): void {
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(descriptor.key)) {
    throw new Error(`Invalid bot runtime config key ${descriptor.key}.`);
  }
  if (descriptor.provider !== secretProvider.provider) {
    throw new Error(`Runtime config provider mismatch for ${descriptor.key}.`);
  }
  if (descriptor.provider !== "OpenBao") {
    throw new Error(`Unsupported bot runtime config provider ${descriptor.provider}.`);
  }
  if (descriptor.secretPath.trim() === "") {
    throw new Error(`Runtime config secretPath is required for ${descriptor.key}.`);
  }
}

function valueFromSecret(
  descriptor: BotRuntimeConfigDescriptorV1,
  secret: BotRuntimeConfigSecretV1 | undefined,
): BotRuntimeConfigValueV1 | undefined {
  if (secret === undefined) {
    return undefined;
  }
  if (typeof secret === "string" || typeof secret === "number" || typeof secret === "boolean") {
    return secret;
  }
  return secret[descriptor.key];
}

function assertValueType(
  descriptor: BotRuntimeConfigDescriptorV1,
  value: BotRuntimeConfigValueV1,
): BotRuntimeConfigValueV1 {
  const expected = descriptor.valueType ?? typeof value;
  if (typeof value !== expected) {
    throw new Error(`Bot runtime config ${descriptor.key} must be ${expected}.`);
  }
  return value;
}
