// Exports playable games and their generation history from a running PlayForge backend into the static
// showcase site (showcase/site), ready for GitHub Pages.
//
// Usage (backend running on 8090, any profile):  node showcase/export.mjs [--api http://localhost:8090] [projectId ...]
//
// For every project with a playtested build (or only the listed projects: the curated showcase):
//   site/games/<id>/play/   ← previews/<id>/  (the built game)
//   site/data/games.json    ← project, runs, steps and the full event log of every run
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '..');
const site = path.join(here, 'site');
const api = process.argv.includes('--api') ? process.argv[process.argv.indexOf('--api') + 1] : 'http://localhost:8090';
const picked = process.argv.slice(2).filter((a, i, all) => !a.startsWith('--') && all[i - 1] !== '--api');

async function getJson(url) {
  const res = await fetch(api + url);
  if (!res.ok) throw new Error(`${url} -> HTTP ${res.status}`);
  return res.json();
}

/** Reads a finished run's SSE stream: the server replays the whole history, then RUN_FINISHED closes it. */
async function runEvents(runId) {
  const res = await fetch(`${api}/api/runs/${runId}/events`, { headers: { Accept: 'text/event-stream' } });
  if (!res.ok) throw new Error(`events of run ${runId} -> HTTP ${res.status}`);
  const events = [];
  const decoder = new TextDecoder();
  let buffer = '';
  for await (const chunk of res.body) {
    buffer += decoder.decode(chunk, { stream: true });
    let idx;
    while ((idx = buffer.indexOf('\n')) >= 0) {
      const line = buffer.slice(0, idx).replace(/\r$/, '');
      buffer = buffer.slice(idx + 1);
      if (!line.startsWith('data:')) continue;
      const e = JSON.parse(line.slice(5));
      events.push({ seq: e.seq, ts: e.ts, type: e.type, source: e.source, payload: e.payload });
      if (e.type === 'RUN_FINISHED') return events;
    }
  }
  return events;
}

let projects = (await getJson('/api/projects')).filter((p) => p.previewAvailable);
if (picked.length) {
  const missing = picked.filter((id) => !projects.some((p) => p.id === id));
  if (missing.length) throw new Error(`no playable project with id ${missing.join(', ')}`);
  projects = picked.map((id) => projects.find((p) => p.id === id));
}
fs.rmSync(path.join(site, 'games'), { recursive: true, force: true });
fs.mkdirSync(path.join(site, 'data'), { recursive: true });

const games = [];
for (const p of projects.sort((a, b) => a.createdAt.localeCompare(b.createdAt))) {
  const runs = [];
  for (const r of p.runs) {
    if (r.status === 'QUEUED' || r.status === 'RUNNING') continue;
    runs.push({ ...r, events: await runEvents(r.id) });
  }
  const play = path.join(site, 'games', p.id, 'play');
  fs.cpSync(path.join(repo, 'previews', p.id), play, { recursive: true });
  // Cover: a mid-game screenshot from the AI player's round beats the harness's first frame (usually a start screen).
  const shotDir = path.join(play, 'playtest');
  if (fs.existsSync(shotDir)) {
    const shots = fs.readdirSync(shotDir).filter((f) => f.endsWith('.png')).sort();
    // Same rule as play.mjs: not the automatic start/end shots, nothing about game over or restarting.
    const midGame = shots.find((f) => !/-(start|end)\.png$/.test(f) && !/over|restart/i.test(f));
    if (midGame) fs.copyFileSync(path.join(shotDir, midGame), path.join(play, 'cover.png'));
  }
  // Load the hi-DPI shim (site/hidpi.js) before the game so its small canvas renders sharply when scaled up.
  const html = path.join(play, 'index.html');
  fs.writeFileSync(html, fs.readFileSync(html, 'utf8').replace(/(\s*)(<script type="module")/,
    '$1<script defer src="../../../hidpi.js?v=__BUILD__"></script>$1$2'));
  games.push({ id: p.id, title: p.title, prompt: p.prompt, createdAt: p.createdAt, runs });
  console.log(`exported ${p.id}  ${p.title}  (${runs.length} run(s), ${runs.reduce((n, r) => n + r.events.length, 0)} events)`);
}

fs.writeFileSync(path.join(site, 'data', 'games.json'), JSON.stringify({ exportedAt: new Date().toISOString(), games }, null, 1));
console.log(`wrote ${games.length} game(s) to ${path.relative(repo, site)}`);
