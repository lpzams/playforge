// Runs the AI player's script in a worker thread, apart from the harness (play.mjs). The script only gets `t`, whose
// methods are messages to the harness; the harness records assertions and screenshots and prints the report after
// terminating this thread, so the script can neither forge the report nor hang the harness with a busy loop.
import { parentPort, workerData } from 'node:worker_threads';
import { pathToFileURL } from 'node:url';

let nextId = 1;
const pending = new Map();
parentPort.on('message', (m) => {
  const p = pending.get(m.id);
  if (!p) return;
  pending.delete(m.id);
  if (m.error) p.reject(new Error(m.error)); else p.resolve(m.value);
});
const call = (method, ...args) => new Promise((resolve, reject) => {
  const id = nextId++;
  pending.set(id, { resolve, reject });
  parentPort.postMessage({ id, method, args });
});

const t = Object.freeze({
  actions: workerData.actions,
  state: () => call('state'),
  start: () => call('start'),
  restart: () => call('restart'),
  wait: (ms) => call('wait', Number(ms) || 0),
  down: (keys) => call('down', keys),
  up: (keys) => call('up', keys),
  press: (keys, ms = 150) => call('press', keys, Number(ms) || 0),
  /** Polls the state here, so the predicate stays a plain function of the script. */
  waitFor: async (predicate, ms = 3000) => {
    const end = Date.now() + Math.min(Number(ms) || 0, 15_000);
    while (Date.now() < end) {
      try { if (predicate(await call('state'))) return true; } catch { /* keep polling */ }
      await new Promise((r) => setTimeout(r, 50));
    }
    return false;
  },
  expect: (cond, message) => { call('expect', !!cond, String(message)).catch(() => {}); return !!cond; },
  shot: (label) => call('shot', String(label)),
  log: (message) => { call('log', String(message)).catch(() => {}); },
});

const mod = await import(pathToFileURL(workerData.script).href);
if (typeof mod.default !== 'function') throw new Error('the play script must default-export a function');
await mod.default(t);
// Let fire-and-forget expect/log messages reach the harness before saying we are done.
await call('done');
