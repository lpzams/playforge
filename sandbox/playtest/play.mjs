// PlayForge AI-play harness: runs a playthrough written by the AI player against a built game.
// Usage: node /opt/playtest/play.mjs --dist <built game dir> --script <module>
// The module default-exports `async function play(t)`. The harness, not the model, records the outcome and prints
// exactly one report as the LAST stdout line:
//   {"aiplay":1,"ran":bool,"error":string|null,"assertions":[{"message","passed"}],
//    "shots":[{"file","label"}],"log":[...],"durationMs":n}
// Screenshots go to <dist>/playtest/NN-label.png so they ship with the build (preview, showcase, judge).
import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { chromium } from 'playwright';

const TOTAL_MS = 80_000;
const PLAY_MS = 45_000;
const PROBE_MS = 10_000;
const STEP_MS = 5_000;
const MAX_SCRIPT_SHOTS = 4;
const MAX_ASSERTIONS = 30;
const MAX_LOG = 50;
const MOUNT = '/game/';
// Same isolation as run.mjs and the backend preview (PreviewController.PREVIEW_CSP).
const PREVIEW_HEADERS = {
  'Content-Security-Policy': 'sandbox allow-scripts allow-pointer-lock',
  'Access-Control-Allow-Origin': '*',
  'X-Content-Type-Options': 'nosniff',
};
const TYPES = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript', '.mjs': 'text/javascript', '.css': 'text/css',
  '.json': 'application/json', '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif',
  '.svg': 'image/svg+xml', '.webp': 'image/webp', '.mp3': 'audio/mpeg', '.ogg': 'audio/ogg', '.wav': 'audio/wav',
  '.wasm': 'application/wasm', '.woff2': 'font/woff2', '.ttf': 'font/ttf',
};

const started = Date.now();
const assertions = [];
const shots = [];
const log = [];
const errors = [];
let ran = false;
let error = null;
let reported = false;

function report() {
  if (reported) return;
  reported = true;
  process.stdout.write('\n' + JSON.stringify({
    aiplay: 1, ran, error, assertions, shots, log, durationMs: Date.now() - started,
  }) + '\n');
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function withTimeout(promise, ms, label) {
  let timer;
  return Promise.race([
    promise,
    new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`${label} timed out`)), ms); }),
  ]).finally(() => clearTimeout(timer));
}

function argValue(name) {
  const i = process.argv.indexOf(name);
  return i >= 0 ? process.argv[i + 1] : undefined;
}

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

const keysOf = (keys) => (Array.isArray(keys) ? keys : [keys]).map(String);

async function main() {
  const dist = argValue('--dist');
  const scriptPath = argValue('--script');
  if (!dist || !fs.existsSync(path.join(dist, 'index.html'))) throw new Error(`no index.html in ${dist}`);
  if (!scriptPath || !fs.existsSync(scriptPath)) throw new Error(`no play script at ${scriptPath}`);
  const shotDir = path.join(dist, 'playtest');
  fs.rmSync(shotDir, { recursive: true, force: true });
  fs.mkdirSync(shotDir, { recursive: true });

  const server = await serve(dist);
  const origin = `http://127.0.0.1:${server.address().port}`;
  const browser = await chromium.launch({ args: ['--no-sandbox'] });
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 600 } });
    page.on('pageerror', (e) => errors.push(e.message));
    page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()); });
    page.on('dialog', (d) => d.dismiss().catch(() => {}));
    await page.route('**/*', (route) => {
      const req = route.request();
      if (req.isNavigationRequest() && !req.url().startsWith(origin)) return route.abort();
      return route.continue();
    });

    await page.goto(origin + MOUNT, { waitUntil: 'load', timeout: 20_000 });
    await page.waitForFunction(() => {
      try { return window.__GAME_PROBE__ && window.__GAME_PROBE__.version === 1 && window.__GAME_PROBE__.getState().status !== 'loading'; } catch { return false; }
    }, null, { timeout: PROBE_MS });

    const actions = await page.evaluate(() => (window.__GAME_PROBE__.actions || [])
      .map((a) => ({ name: String(a.name), keys: (a.keys || []).map(String) })));

    let shotNo = 0;
    const shoot = async (label) => {
      let target = page;
      let best = 0;
      for (const c of await page.locator('canvas').all()) {
        const box = await c.boundingBox({ timeout: STEP_MS }).catch(() => null);
        if (box && box.width * box.height > best) { best = box.width * box.height; target = c; }
      }
      const safe = String(label).toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 30) || 'shot';
      const file = `playtest/${String(++shotNo).padStart(2, '0')}-${safe}.png`;
      await withTimeout(target.screenshot({ path: path.join(dist, file), timeout: STEP_MS }), STEP_MS, 'screenshot');
      shots.push({ file, label: String(label).slice(0, 60) });
    };

    const getState = () => withTimeout(page.evaluate(() => window.__GAME_PROBE__.getState()), STEP_MS, 'state()');
    let scriptShots = 0;
    const t = Object.freeze({
      actions,
      state: getState,
      start: () => withTimeout(page.evaluate(() => window.__GAME_PROBE__.start()), STEP_MS, 'start()'),
      restart: () => withTimeout(page.evaluate(() => window.__GAME_PROBE__.restart()), STEP_MS, 'restart()'),
      wait: (ms) => sleep(Math.max(0, Math.min(Number(ms) || 0, 10_000))),
      down: async (keys) => { for (const k of keysOf(keys)) await withTimeout(page.keyboard.down(k), STEP_MS, `down ${k}`); },
      up: async (keys) => { for (const k of keysOf(keys)) await withTimeout(page.keyboard.up(k), STEP_MS, `up ${k}`); },
      press: async (keys, ms = 150) => {
        await t.down(keys);
        await sleep(Math.max(0, Math.min(Number(ms) || 0, 5_000)));
        await t.up(keys);
      },
      waitFor: async (predicate, ms = 3_000) => {
        const end = Date.now() + Math.min(Number(ms) || 0, 15_000);
        while (Date.now() < end) {
          try { if (predicate(await getState())) return true; } catch { /* keep polling */ }
          await sleep(50);
        }
        return false;
      },
      expect: (cond, message) => {
        if (assertions.length < MAX_ASSERTIONS) assertions.push({ message: String(message).slice(0, 300), passed: !!cond });
        return !!cond;
      },
      shot: async (label) => {
        if (scriptShots >= MAX_SCRIPT_SHOTS) return;
        scriptShots++;
        await shoot(label);
      },
      log: (msg) => { if (log.length < MAX_LOG) log.push(String(msg).slice(0, 300)); },
    });

    await shoot('start');
    const mod = await import(pathToFileURL(path.resolve(scriptPath)).href);
    if (typeof mod.default !== 'function') throw new Error('the play script must default-export a function');
    const errorsBefore = errors.length;
    try {
      await withTimeout(Promise.resolve(mod.default(t)), PLAY_MS, 'play script');
      ran = true;
    } finally {
      await shoot('end').catch(() => {});
    }
    // Errors thrown by the game while it was being played are a real finding, not a script problem.
    const gameErrors = errors.slice(errorsBefore);
    if (gameErrors.length) {
      assertions.push({ message: `no runtime errors while playing (got: ${gameErrors.slice(0, 2).join(' | ').slice(0, 250)})`, passed: false });
    }
  } finally {
    server.close();
    browser.close().catch(() => {});
  }
}

const deadline = setTimeout(() => {
  error = error || `ai-play timed out after ${TOTAL_MS / 1000}s`;
  report();
  process.exit(0);
}, TOTAL_MS);

main()
  .catch((e) => { error = String(e && (e.message || e)).slice(0, 1000); })
  .finally(() => {
    clearTimeout(deadline);
    report();
    process.exit(0);
  });
