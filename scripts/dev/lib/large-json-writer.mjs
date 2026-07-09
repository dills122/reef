import { createWriteStream, mkdirSync } from "node:fs";
import { dirname } from "node:path";
import { once } from "node:events";

export async function writeJsonFileStreaming(path, value, options = {}) {
  mkdirSync(dirname(path), { recursive: true });
  const stream = createWriteStream(path, { encoding: "utf8" });
  try {
    const writer = new JsonStreamWriter(stream, options);
    await writer.writeValue(value, 0, false);
    await writer.write("\n");
    await writer.flush();
    stream.end();
    await once(stream, "finish");
  } catch (error) {
    stream.destroy();
    throw error;
  }
}

class JsonStreamWriter {
  constructor(stream, options) {
    this.stream = stream;
    this.space = normalizeSpace(options.space ?? 2);
    this.seen = new Set();
    this.buffer = [];
    this.bufferBytes = 0;
    this.flushBytes = Math.max(1024, Number(options.flushBytes ?? 65536));
  }

  async write(chunk) {
    this.buffer.push(chunk);
    this.bufferBytes += Buffer.byteLength(chunk);
    if (this.bufferBytes >= this.flushBytes) {
      await this.flush();
    }
  }

  async flush() {
    if (this.buffer.length === 0) {
      return;
    }
    const chunk = this.buffer.join("");
    this.buffer = [];
    this.bufferBytes = 0;
    if (!this.stream.write(chunk)) {
      await once(this.stream, "drain");
    }
  }

  async writeValue(value, depth, inArray) {
    const normalized = normalizeJsonValue(value);
    if (normalized === undefined) {
      if (inArray) {
        await this.write("null");
        return;
      }
      throw new TypeError("Cannot serialize undefined as a JSON document");
    }
    if (normalized === null || typeof normalized !== "object") {
      await this.write(JSON.stringify(normalized));
      return;
    }
    if (this.seen.has(normalized)) {
      throw new TypeError("Converting circular structure to JSON");
    }
    this.seen.add(normalized);
    try {
      if (Array.isArray(normalized)) {
        await this.writeArray(normalized, depth);
      } else {
        await this.writeObject(normalized, depth);
      }
    } finally {
      this.seen.delete(normalized);
    }
  }

  async writeArray(values, depth) {
    if (values.length === 0) {
      await this.write("[]");
      return;
    }
    await this.write("[");
    for (let index = 0; index < values.length; index += 1) {
      if (this.space.length === 0) {
        if (index > 0) await this.write(",");
      } else {
        await this.write(index === 0 ? "\n" : ",\n");
        await this.writeIndent(depth + 1);
      }
      await this.writeValue(values[index], depth + 1, true);
    }
    if (this.space.length > 0) {
      await this.write("\n");
      await this.writeIndent(depth);
    }
    await this.write("]");
  }

  async writeObject(value, depth) {
    const entries = Object.entries(value)
      .filter(([, entryValue]) => normalizeJsonValue(entryValue) !== undefined);
    if (entries.length === 0) {
      await this.write("{}");
      return;
    }
    await this.write("{");
    for (let index = 0; index < entries.length; index += 1) {
      const [key, entryValue] = entries[index];
      if (this.space.length === 0) {
        if (index > 0) await this.write(",");
      } else {
        await this.write(index === 0 ? "\n" : ",\n");
        await this.writeIndent(depth + 1);
      }
      await this.write(JSON.stringify(key));
      await this.write(this.space.length === 0 ? ":" : ": ");
      await this.writeValue(entryValue, depth + 1, false);
    }
    if (this.space.length > 0) {
      await this.write("\n");
      await this.writeIndent(depth);
    }
    await this.write("}");
  }

  async writeIndent(depth) {
    if (this.space.length > 0 && depth > 0) {
      await this.write(this.space.repeat(depth));
    }
  }
}

function normalizeJsonValue(value) {
  if (value !== null && typeof value === "object" && typeof value.toJSON === "function") {
    return value.toJSON();
  }
  if (typeof value === "undefined" || typeof value === "function" || typeof value === "symbol") {
    return undefined;
  }
  if (typeof value === "bigint") {
    throw new TypeError("Do not know how to serialize a BigInt");
  }
  return value;
}

function normalizeSpace(space) {
  if (typeof space === "number") {
    return " ".repeat(Math.min(10, Math.max(0, Math.floor(space))));
  }
  if (typeof space === "string") {
    return space.slice(0, 10);
  }
  return "";
}
