// Process-crash reference harness. Not a broker, SQL WAL or host-loss durability proof.
import { closeSync, existsSync, fsyncSync, openSync, readFileSync, writeSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { apply, canonical, digest, genesis } from './arena-credit-model.mjs';

export function replay(file, config, expectedHead = null) {
  let state = genesis(config), sequence = 0, previous = digest(state);
  const content = existsSync(file) ? readFileSync(file, 'utf8') : '';
  if (content && !content.endsWith('\n')) throw new Error('INCOMPLETE_JOURNAL');
  for (const line of content.split('\n').filter(Boolean)) {
    const { hash, ...frame } = JSON.parse(line);
    if (hash !== digest(frame) || frame.sequence !== sequence + 1 || frame.previous !== previous
      || frame.before !== digest(state)) throw new Error('JOURNAL_CHAIN_MISMATCH');
    const next = apply(state, frame.command);
    if (frame.result !== next.result || frame.after !== digest(next.state)) throw new Error('REPLAY_MISMATCH');
    state = next.state;
    sequence++;
    previous = hash;
  }
  if (expectedHead && (sequence !== expectedHead.sequence || previous !== expectedHead.previous)) {
    throw new Error('COMMITTED_HEAD_MISMATCH');
  }
  return { state, sequence, previous };
}

const killAt = (actual, expected) => { if (actual === expected) process.kill(process.pid, 'SIGKILL'); };
function writeAll(fd, buffer) {
  let offset = 0;
  while (offset < buffer.length) offset += writeSync(fd, buffer, offset, buffer.length - offset);
}
export function transact(file, config, command, cut = '') {
  const recovered = replay(file, config);
  const next = apply(recovered.state, command);
  const frame = { sequence: recovered.sequence + 1, previous: recovered.previous,
    before: digest(recovered.state), command, result: next.result, after: digest(next.state) };
  const bytes = Buffer.from(`${canonical({ ...frame, hash: digest(frame) })}\n`);
  killAt(cut, 'before-write');
  const fd = openSync(file, 'a');
  try {
    if (cut === 'torn-write') {
      writeAll(fd, bytes.subarray(0, Math.floor(bytes.length / 2)));
      killAt(cut, 'torn-write');
    }
    writeAll(fd, bytes);
    fsyncSync(fd);
    killAt(cut, 'after-sync');
  } finally { closeSync(fd); }
  return next;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const [file, inputFile, cut] = process.argv.slice(2);
  const { config, command } = JSON.parse(readFileSync(inputFile, 'utf8'));
  const next = transact(file, config, command, cut);
  writeSync(1, `${canonical({ acknowledged: next.result, digest: digest(next.state) })}\n`);
  killAt(cut, 'after-ack');
}
