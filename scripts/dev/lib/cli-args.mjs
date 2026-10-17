// Shared CLI argument parsing for dev scripts.

/**
 * Parses `--key value`, `--key=value` and bare `--flag` arguments into a plain object.
 * A flag with no value (end of argv, or followed by another `--option`) becomes the string "true".
 * Positional arguments throw. Only text up to a second `=` is kept for `--key=a=b` (legacy behavior
 * of the per-script copies this replaced).
 */
export function parseKeyValueArgs(argv) {
  const out = {};
  for (let index = 0; index < argv.length; index++) {
    const arg = argv[index];
    if (!arg.startsWith("--")) {
      throw new Error(`unexpected positional argument: ${arg}`);
    }
    const keyValue = arg.slice(2);
    const [key, inlineValue] = keyValue.split("=", 2);
    if (inlineValue != null) {
      out[key] = inlineValue;
      continue;
    }
    const next = argv[index + 1];
    if (!next || next.startsWith("--")) {
      out[key] = "true";
      continue;
    }
    out[key] = next;
    index++;
  }
  return out;
}
