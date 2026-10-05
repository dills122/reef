import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, mkdir, writeFile, readFile, rm, realpath } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { collectInventoryFile, readBoundedJSON } from './physical-adapter.mjs';

test('bounded inventory reader rejects oversize and invalid JSON', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'physical-adapter-json-')));
  try {
    const file = path.join(root, 'raw.json'); await writeFile(file, '{"actual":true}');
    assert.deepEqual(await readBoundedJSON(file, 32), { actual: true });
    await assert.rejects(readBoundedJSON(file, 4), /SIZE/);
    await writeFile(file, 'not JSON'); await assert.rejects(readBoundedJSON(file, 32));
  } finally { await rm(root, { recursive: true, force: true }); }
});
test('adapter publishes collector evidence exclusively within frozen proof path', async () => {
  const root = await realpath(await mkdtemp(path.join(tmpdir(), 'physical-adapter-file-')));
  try {
    const proof = path.join(root, 'proof'); await mkdir(proof);
    const manifestPath = path.join(root, 'manifest.json'), inventoryPath = path.join(proof, 'inventory.json'), output = path.join(proof, 'allocation.json');
    const manifest = { ownedRoot: root, localResources: [{ category: 'proof', path: proof }] };
    await writeFile(manifestPath, JSON.stringify(manifest)); await writeFile(inventoryPath, '{"raw":"mock unit control"}');
    const collect = async (input, deps) => {
      assert.deepEqual(input, manifest); assert.deepEqual(await deps.metadataSnapshot(), { raw: 'mock unit control' });
      return { result: 'mock unit control only' };
    };
    await collectInventoryFile(manifestPath, inventoryPath, output, { collect });
    assert.deepEqual(JSON.parse(await readFile(output, 'utf8')), { result: 'mock unit control only' });
    await assert.rejects(collectInventoryFile(manifestPath, inventoryPath, output, { collect }), /EEXIST/);
    await assert.rejects(collectInventoryFile(manifestPath, inventoryPath, path.join(root, 'unowned.json'), { collect }), /OWNED/);
  } finally { await rm(root, { recursive: true, force: true }); }
});
