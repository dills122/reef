import { open, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { collectPhysicalEvidence } from './physical-evidence.mjs';
import { hostMonotonicMs } from './lib/external-faults.mjs';

export async function readBoundedBytes(file, maximum) {
  if (!Number.isSafeInteger(maximum) || maximum <= 0 || maximum > 8 * 1024 * 1024) throw Error('PHYSICAL_ADAPTER_INPUT_LIMIT');
  const handle = await open(file, 'r');
  try {
    const buffer = Buffer.alloc(maximum + 1);
    let length = 0;
    while (length < buffer.length) {
      const { bytesRead } = await handle.read(buffer, length, buffer.length - length, length);
      if (!bytesRead) break;
      length += bytesRead;
    }
    if (length > maximum) throw Error('PHYSICAL_ADAPTER_INPUT_SIZE');
    return buffer.subarray(0, length);
  } finally { await handle.close(); }
}

export async function readBoundedJSON(file, maximum) {
  return JSON.parse((await readBoundedBytes(file, maximum)).toString('utf8'));
}

export async function collectInventoryFile(manifestPath, inventoryPath, outputPath, { collect = collectPhysicalEvidence } = {}) {
  const manifest = await readBoundedJSON(manifestPath, 1024 * 1024);
  const root = await realpath(manifest.ownedRoot), inventory = await realpath(inventoryPath);
  const proof = manifest.localResources.find(r => r.category === 'proof')?.path;
  const output = path.resolve(outputPath), parent = await realpath(path.dirname(output));
  if (root !== manifest.ownedRoot || !inventory.startsWith(root + path.sep) || !proof
    || parent !== path.dirname(output) || !(output.startsWith(proof + path.sep))
    || await realpath(proof) !== proof) throw Error('PHYSICAL_ADAPTER_OWNED_PATH_REQUIRED');
  const snapshot = await readBoundedJSON(inventory, 8 * 1024 * 1024);
  try {
    const result = await collect(manifest, { metadataSnapshot: async () => snapshot });
    await writeFile(output, JSON.stringify(result, null, 2) + '\n', { flag: 'wx' });
    return result;
  } catch (error) {
    if (error.evidence) await writeFile(output + '.failed.json', JSON.stringify({ error: String(error), evidence: error.evidence }, null, 2) + '\n', { flag: 'wx' });
    throw error;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) {
  try {
    const args = process.argv.slice(2);
    if (args.length === 1 && args[0] === '--clock') {
      console.log(JSON.stringify({ clockScope: 'host-monotonic-ms', sampledAtMs: hostMonotonicMs(), node: process.versions.node, uv: process.versions.uv, platform: process.platform }));
    } else if (args.length === 4 && args[0] === '--collect') {
      await collectInventoryFile(...args.slice(1)); console.log(JSON.stringify({ artifact: path.resolve(args[3]) }));
    } else throw Error('Usage: physical-adapter.mjs --clock | --collect MANIFEST.json INVENTORY.json UNIQUE_OUTPUT.json');
  } catch (error) { console.error(String(error)); process.exitCode = 1; }
}
