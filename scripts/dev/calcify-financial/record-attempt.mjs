import { spawn } from 'node:child_process';
import { mkdir, writeFile, appendFile, access } from 'node:fs/promises';
import { resolve } from 'node:path';
import { execFileSync } from 'node:child_process';

// Record every attempt, including nonzero exit and complete stdout/stderr.
const [id, cwdArg, command, ...args] = process.argv.slice(2);
if (!id || !cwdArg || !command || !/^[a-z0-9-]+$/.test(id)) throw Error('usage: record-attempt.mjs ID CWD COMMAND [ARGS]');
const root = resolve(import.meta.dirname, '../../..');
const cwd = resolve(root, cwdArg);
const dir = resolve(root, 'docs/evidence/calcify-financial-sprint1/raw');
await mkdir(dir, { recursive: true });
const collision = await access(resolve(dir, `${id}.stdout.log`)).then(() => true, () => false);
if (collision) throw Error(`attempt ${id} exists; choose new ID to preserve prior evidence`);
const started = new Date().toISOString();
const head = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
const child = spawn(command, args, { cwd, stdio: ['ignore', 'pipe', 'pipe'] });
let stdout = '', stderr = '';
child.stdout.on('data', x => { stdout += x; process.stdout.write(x); });
child.stderr.on('data', x => { stderr += x; process.stderr.write(x); });
child.on('error', x => { stderr += String(x); });
const result = await new Promise(r => child.on('close', (code, signal) => r({ code, signal })));
await writeFile(resolve(dir, `${id}.stdout.log`), stdout);
await writeFile(resolve(dir, `${id}.stderr.log`), stderr);
await appendFile(resolve(dir, 'attempts.jsonl'), JSON.stringify({ id, cwd, command, args, started, finished: new Date().toISOString(), head, javaHome: process.env.JAVA_HOME ?? null, ...result }) + '\n');
process.exitCode = result.code ?? 1;
