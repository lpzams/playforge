// PlayForge playtest harness (v1): hard checks driven by the probe protocol.
// Usage: node /opt/playtest/run.mjs --dist <built game dir>
// Prints exactly one report as the LAST stdout line:
//   {"playtest":1,"passed":bool,"failures":[...],"checks":[{"name","passed","detail"}],"durationMs":n}
// Checks: load, probe, no_errors, not_blank, action:<name> (per action), restart.
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { chromium } from 'playwright';
import { PNG } from 'pngjs';

const TOTAL_MS = 90_000;
const PROBE_MS = 10_000;
const STEP_MS = 5_000;
const HOLD_MS = 400;
const BLANK_STDDEV = 2;
const MOUNT = '/game/';

const started = Date.now();
const checks = [];
const errors = [];
const badResponses = [];
let reported = false;

function check(name, passed, detail = '') {
  checks.push({ name, passed: !!passed, detail: String(detail).slice(0, 1000) });
}

function report(extraFailure) {
  if (reported) return;
  reported = true;
  const failures = checks.filter((c) => !c.passed).map((c) => `${c.name}: ${c.detail}`);
  if (extraFailure) failures.push(extraFailure);
  process.stdout.write('\n' + JSON.stringify({
    playtest: 1,
    passed: failures.length === 0 && checks.length > 0,
    failures,
    checks,
    durationMs: Date.now() - started,
  }) + '\n');
}

function withTimeout(promise, ms, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${label} timed out (page unresponsive?)`)), ms); }),
  ]).finally(() => clearTimeout(timer));
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.svg': 'image/svg+xml', '.webp': 'image/webp', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.wav': 'audio/wav',
  '.wasm': 'application/wasm', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
};

/** Serves only files inside dist, mounted under /game/. Everything else is 404. */
function serve(dist) {
  const root = path.resolve(dist);
  const server = http.createServer((req, res) => {
    const url = new URL(req.url, 'http://x');
    let rel = decodeURIComponent(url.pathname);
    if (!rel.startsWith(MOUNT)) { res.writeHead(404).end(); return; }
    rel = rel.slice(MOUNT.length) || 'index.html';
    if (rel.endsWith('/')) rel += 'index.html';
    const file = path.resolve(root, rel);
    if (!file.startsWith(root + path.sep) || !fs.existsSync(file) || !fs.statSync(file).isFile()) {
      res.writeHead(404).end();
      return;
    }
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream' });
    fs.createReadStream(file).pipe(res);
  });
  return new Promise((resolve) => server.listen(0, '127.0.0.1', () => resolve(server)));
}

function grayStddev(pngBuffer) {
  const png = PNG.sync.read(pngBuffer);
  const n = png.width * png.height;
  let sum = 0;
  let sumSq = 0;
  for (let i = 0; i < png.data.length; i += 4) {
    const g = 0.299 * png.data[i] + 0.587 * png.data[i + 1] + 0.114 * png.data[i + 2];
    sum += g;
    sumSq += g * g;
  }
  const mean = sum / n;
  return Math.sqrt(Math.max(0, sumSq / n - mean * mean));
}

/** Did pressing a key visibly change the game? Status or entities (count or position); score alone doesn't count. */
function stateChanged(before, after) {
  if (!before || !after) return false;
  if (before.status !== after.status) return true;
  const a = Array.isArray(before.entities) ? before.entities : [];
  const b = Array.isArray(after.entities) ? after.entities : [];
  if (a.length !== b.length) return true;
  const byId = new Map(a.map((e) => [String(e.id), e]));
  return b.some((e) => {
    const p = byId.get(String(e.id));
    return !p || Math.abs((p.x ?? 0) - (e.x ?? 0)) > 0.5 || Math.abs((p.y ?? 0) - (e.y ?? 0)) > 0.5;
  });
}

async function main() {
  const dist = argValue('--dist');
  if (!dist || !fs.existsSync(path.join(dist, 'index.html'))) {
    check('load', false, `no index.html in ${dist}`);
    return;
  }
  const server = await serve(dist);
  const origin = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('response', (r) => { if (r.status() >= 400) badResponses.push(`${r.status()} ${r.url()}`); });
    page.on('requestfailed', (r) => badResponses.push(`failed ${r.url()}`));
    page.on('dialog', (d) => d.dismiss().catch(() => {}));
    await page.route('**/*', (route) => {
      const req = route.request();
      if (req.isNavigationRequest() && !req.url().startsWith(origin)) return route.abort();
      return route.continue();
    });

    // load
    try {
      const resp = await page.goto(origin + MOUNT, { waitUntil: 'load', timeout: 20_000 });
      await sleep(500);
      const ok = resp && resp.ok() && badResponses.length === 0;
      check('load', ok, ok ? '' : badResponses.length ? badResponses.slice(0, 5).join('; ') : `HTTP ${resp && resp.status()}`);
    } catch (e) {
      check('load', false, e.message);
    }

    // probe
    let probe = null;
    try {
      await page.waitForFunction(() => window.__GAME_PROBE__ && window.__GAME_PROBE__.version === 1, null, { timeout: PROBE_MS });
      probe = await withTimeout(page.evaluate(() => ({
        state: window.__GAME_PROBE__.getState(),
        actions: (window.__GAME_PROBE__.actions || []).map((a) => ({ name: String(a.name), keys: (a.keys || []).map(String) })),
      })), STEP_MS, 'reading probe');
      const okStatus = ['ready', 'playing'].includes(probe.state && probe.state.status);
      check('probe', okStatus, okStatus ? '' : `unexpected initial status: ${JSON.stringify(probe.state && probe.state.status)}`);
    } catch (e) {
      check('probe', false, e.name === 'TimeoutError' ? 'window.__GAME_PROBE__ not found within 10s' : e.message);
      probe = null;
    }

    // not_blank
    try {
      const shot = await page.screenshot({ timeout: STEP_MS });
      const sd = grayStddev(shot);
      check('not_blank', sd >= BLANK_STDDEV, `gray stddev ${sd.toFixed(2)}`);
    } catch (e) {
      check('not_blank', false, e.message);
    }

    const getState = () => withTimeout(page.evaluate(() => window.__GAME_PROBE__.getState()), STEP_MS, 'getState');

    // action:<name>
    if (probe) {
      if (probe.actions.length === 0) check('action:none', false, 'probe declares no actions');
      for (const action of probe.actions) {
        const name = `action:${action.name}`;
        try {
          let s = await getState();
          if (s.status === 'gameover') {
            await withTimeout(page.evaluate(() => window.__GAME_PROBE__.restart()), STEP_MS, 'restart');
            s = await getState();
          }
          if (s.status === 'ready') {
            await withTimeout(page.evaluate(() => window.__GAME_PROBE__.start()), STEP_MS, 'start');
            await sleep(100);
          }
          const before = await getState();
          for (const k of action.keys) await withTimeout(page.keyboard.down(k), STEP_MS, `key ${k}`);
          await sleep(HOLD_MS);
          for (const k of action.keys) await withTimeout(page.keyboard.up(k), STEP_MS, `key ${k}`);
          const after = await getState();
          const changed = stateChanged(before, after);
          check(name, changed, changed ? '' : `pressing ${action.keys.join('+')} for ${HOLD_MS}ms changed neither entities nor status`);
        } catch (e) {
          check(name, false, e.message);
        }
      }

      // restart
      try {
        await withTimeout(page.evaluate(() => window.__GAME_PROBE__.restart()), STEP_MS, 'restart');
        let ok = false;
        let last;
        for (let i = 0; i < 10 && !ok; i++) {
          await sleep(100);
          last = await getState();
          ok = ['ready', 'playing'].includes(last.status);
        }
        check('restart', ok, ok ? '' : `status after restart(): ${JSON.stringify(last && last.status)}`);
      } catch (e) {
        check('restart', false, e.message);
      }
    }

    // no_errors (collected over the whole session)
    check('no_errors', errors.length === 0, errors.slice(0, 3).join(' | '));
  } finally {
    server.close();
    browser.close().catch(() => {});
  }
}

const deadline = setTimeout(() => {
  report(`playtest timed out after ${TOTAL_MS / 1000}s (page unresponsive?)`);
  process.exit(0);
}, TOTAL_MS);

main()
  .catch((e) => check('harness', false, e.stack || e.message))
  .finally(() => {
    clearTimeout(deadline);
    report();
    process.exit(0);
  });
