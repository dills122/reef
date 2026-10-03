import assert from 'node:assert/strict';
import test from 'node:test';
import { PassThrough } from 'node:stream';
import { execFileSync, spawnSync } from 'node:child_process';
import { cp, mkdir, mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { captureOutput } from './lib/output-capture.mjs';
import { createHash } from 'node:crypto';

test('stdout and stderr retain split UTF-8 and invalid bytes exactly', () => {
  for (const chunks of [
    [Buffer.from([0xc3]), Buffer.from([0xa9, 0xff, 0x00])],
    [Buffer.from([0xf0, 0x9f]), Buffer.from([0x8c]), Buffer.from([0x8a, 0xfe])]
  ]) {
    const stream = new PassThrough();
    const echoed = [];
    const captured = captureOutput(stream, { write: bytes => echoed.push(Buffer.from(bytes)) });
    chunks.forEach(chunk => stream.write(chunk));
    stream.end();
    assert.deepEqual(captured(), Buffer.concat(chunks));
    assert.deepEqual(Buffer.concat(echoed), Buffer.concat(chunks));
  }
});

test('recorder persists binary output, failures and separate spawn diagnostics', async () => {
  const root = await mkdtemp(join(tmpdir(), 'calcify-recorder-test-'));
  try {
    const scriptDir = join(root, 'scripts/dev/calcify-financial');
    await mkdir(scriptDir, { recursive: true });
    await cp(new URL('./record-attempt.mjs', import.meta.url), join(scriptDir, 'record-attempt.mjs'));
    await cp(new URL('./lib', import.meta.url), join(scriptDir, 'lib'), { recursive: true });
    execFileSync('git', ['init', '--quiet'], { cwd: root });
    execFileSync('git', ['-c', 'user.name=Recorder Test', '-c', 'user.email=recorder@example.invalid', '-c', 'core.hooksPath=/dev/null', '-c', 'commit.gpgsign=false', 'commit', '--quiet', '--allow-empty', '-m', 'test fixture'], { cwd: root });
    const script = join(scriptDir, 'record-attempt.mjs');
    const raw = join(root, 'docs/evidence/calcify-financial-sprint1/raw');
    const code = 'process.stdout.write(Buffer.from([0xc3,0xa9,0xff,0])); process.stderr.write(Buffer.from([0xf0,0x9f,0x8c,0x8a,0xfe])); process.exitCode=7;';
    const recorded = spawnSync(process.execPath, [script, 'binary', '.', process.execPath, '-e', code]);
    assert.equal(recorded.status, 7);
    const stdout = await readFile(join(raw, 'binary.stdout.log'));
    const stderr = await readFile(join(raw, 'binary.stderr.log'));
    assert.deepEqual(stdout, Buffer.from([0xc3, 0xa9, 0xff, 0]));
    assert.deepEqual(stderr, Buffer.from([0xf0, 0x9f, 0x8c, 0x8a, 0xfe]));
    assert.deepEqual(recorded.stdout, stdout);
    assert.deepEqual(recorded.stderr, stderr);
    const duplicate = spawnSync(process.execPath, [script, 'binary', '.', process.execPath, '--version']);
    assert.equal(duplicate.status, 1);
    assert.deepEqual(await readFile(join(raw, 'binary.stdout.log')), stdout);
    const missing = spawnSync(process.execPath, [script, 'missing', '.', join(root, 'absent-command')]);
    assert.equal(missing.status, 1);
    assert.equal((await readFile(join(raw, 'missing.stderr.log'))).length, 0);
    const attempts = (await readFile(join(raw, 'attempts.jsonl'), 'utf8')).trim().split('\n').map(JSON.parse);
    assert.equal(attempts.length, 2);
    assert.equal(attempts[0].code, 7);
    assert.equal(attempts[0].spawnError, null);
    assert.equal(attempts[0].recorderSha256, createHash('sha256').update(await readFile(script)).digest('hex'));
    assert.equal(attempts[0].captureSha256, createHash('sha256').update(await readFile(join(scriptDir, 'lib/output-capture.mjs'))).digest('hex'));
    assert.match(attempts[1].spawnError, /ENOENT/);
    assert.notEqual(attempts[1].code, 0);
  } finally {
    await rm(root, { recursive: true, force: true });
  }
});
