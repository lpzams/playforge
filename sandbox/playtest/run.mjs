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
const SAMPLE_MS = 100;
const LIFECYCLE_ACTIONS = /^(start|restart|reset|retry|pause|resume|unpause|menu|begin|play[\s_-]?again)$/i;
const MOUNT = '/game/';
// Same isolation the backend's /preview endpoint applies (PreviewController.PREVIEW_CSP), so that
// "passes the playtest" implies "works in the preview" (e.g. localStorage throws in both).
const PREVIEW_HEADERS = {
  'Content-Security-Policy': 'sandbox allow-scripts allow-pointer-lock',
  'Access-Control-Allow-Origin': '*',
  'X-Content-Type-Options': 'nosniff',
};

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
    res.writeHead(200, { 'Content-Type': TYPES[path.extname(file).toLowerCase()] || 'application/octet-stream', ...PREVIEW_HEADERS });
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

function entitiesOf(state, playersOnly) {
  const all = state && Array.isArray(state.entities) ? state.entities.filter(Boolean) : [];
  return playersOnly ? all.filter((e) => e.type === 'player') : all;
}

/**
 * Ids of entities that appeared, disappeared or moved between two states. Score is ignored: time-based scores
 * change with or without input.
 */
function changedIds(a, b, playersOnly) {
  const before = new Map(entitiesOf(a, playersOnly).map((e) => [String(e.id), e]));
  const after = new Map(entitiesOf(b, playersOnly).map((e) => [String(e.id), e]));
  const ids = new Set();
  for (const [id, e] of after) {
    const p = before.get(id);
    if (!p || Math.abs((p.x ?? 0) - (e.x ?? 0)) > 0.5 || Math.abs((p.y ?? 0) - (e.y ?? 0)) > 0.5) ids.add(id);
  }
  for (const id of before.keys()) if (!after.has(id)) ids.add(id);
  return ids;
}

/**
 * What changed during a window, relative to its first state: union of changed entity ids over all samples,
 * plus whether the status changed at any point. Sampling (not just before/after) catches short actions
 * such as a jump that has landed by the time the key is released.
 */
function changesOver(first, samples, playersOnly) {
  const ids = new Set();
  let status = false;
  for (const s of samples) {
    for (const id of changedIds(first, s, playersOnly)) ids.add(id);
    if (s && first && s.status !== first.status) status = true;
  }
  return { ids, status };
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
      const probeStart = Date.now();
      await page.waitForFunction(() => window.__GAME_PROBE__ && window.__GAME_PROBE__.version === 1, null, { timeout: PROBE_MS });
      // 'loading' is a valid initial status (e.g. Phaser preload): wait for it to end within the same window.
      try {
        await page.waitForFunction(() => {
          try { return window.__GAME_PROBE__.getState().status !== 'loading'; } catch { return false; }
        }, null, { timeout: Math.max(1000, PROBE_MS - (Date.now() - probeStart)) });
      } catch (e) {
        if (e.name === 'TimeoutError') throw new Error(`status still 'loading' after ${PROBE_MS / 1000}s`);
        throw e;
      }
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

    // not_blank: measured on the largest canvas (page styling around it must not count), else the whole page
    try {
      let target = page;
      const canvases = await withTimeout(page.locator('canvas').all(), STEP_MS, 'finding canvas');
      let bestArea = 0;
      for (const c of canvases) {
        const box = await c.boundingBox({ timeout: STEP_MS }).catch(() => null);
        if (box && box.width * box.height > bestArea) { bestArea = box.width * box.height; target = c; }
      }
      const shot = await target.screenshot({ timeout: STEP_MS });
      const sd = grayStddev(shot);
      check('not_blank', sd >= BLANK_STDDEV, `gray stddev ${sd.toFixed(2)}`);
      // The canvas shot doubles as the game's cover image (shipped with the build to preview and showcase).
      if (sd >= BLANK_STDDEV) {
        try { fs.writeFileSync(path.join(dist, 'cover.png'), shot); } catch { /* read-only dist: no cover */ }
      }
    } catch (e) {
      check('not_blank', false, e.message);
    }

    const getState = () => withTimeout(page.evaluate(() => window.__GAME_PROBE__.getState()), STEP_MS, 'getState');

    // action:<name>
    if (probe) {
      if (probe.actions.length === 0) check('action:none', false, 'probe declares no actions');
      for (const action of probe.actions) {
        const name = `action:${action.name}`;
        // Start/restart/pause are lifecycle controls, covered by start()/restart(); playing them as in-game
        // controls produced false failures that pushed the coder to change the game's design.
        if (LIFECYCLE_ACTIONS.test(action.name)) {
          check(name, true, 'lifecycle action (covered by start()/restart()), not played as a control');
          continue;
        }
        try {
          let s = await getState();
          if (s.status === 'gameover') {
            await withTimeout(page.evaluate(() => window.__GAME_PROBE__.restart()), STEP_MS, 'restart');
            s = await getState();
          }
          if (s.status === 'ready' || s.status === 'paused') {
            await withTimeout(page.evaluate(() => window.__GAME_PROBE__.start()), STEP_MS, 'start');
            await sleep(SAMPLE_MS);
          }
          const sampleFor = async (ms) => {
            const out = [];
            const end = Date.now() + ms;
            while (Date.now() < end) {
              await sleep(SAMPLE_MS);
              out.push(await getState());
            }
            return out;
          };

          // Baseline: what changes over the same time WITHOUT input (falling rocks, drifting clouds...).
          const base0 = await getState();
          const playersOnly = entitiesOf(base0, false).some((e) => e.type === 'player');
          const baseline = changesOver(base0, await sampleFor(HOLD_MS), playersOnly);

          // With the keys held, sampled throughout so short actions (jump, shoot) are seen.
          const key0 = await getState();
          for (const k of action.keys) await withTimeout(page.keyboard.down(k), STEP_MS, `key ${k}`);
          const held = await sampleFor(HOLD_MS);
          for (const k of action.keys) await withTimeout(page.keyboard.up(k), STEP_MS, `key ${k}`);
          held.push(await getState());
          const keyed = changesOver(key0, held, playersOnly);

          const novel = [...keyed.ids].filter((id) => !baseline.ids.has(id));
          const ok = novel.length > 0 || (keyed.status && !baseline.status);
          const scope = playersOnly ? "entities of type 'player'" : 'entities';
          check(name, ok, ok
            ? `changed: ${novel.join(', ') || 'status'}`
            : `pressing ${action.keys.join('+')} for ${HOLD_MS}ms changed no ${scope} or status beyond what changes without input`);
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
