// Shared by the plaza (index.html) and the game page (game.html).
// All text from the data (AI output included) is set with textContent only.
const REPO_URL = document.querySelector('meta[name="repo-url"]')?.content || 'https://github.com/';
const SANDBOX = 'allow-scripts allow-pointer-lock';

const CHECK_NAMES = {
  build: '构建', load: '加载', probe: '探针', not_blank: '画面', no_errors: '无报错', restart: '重新开始', harness: '试玩脚本',
};

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null) continue;
    if (k === 'class') node.className = v;
    else node.setAttribute(k, v);
  }
  for (const c of children) if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

const duration = (ms) => (ms < 1000 ? '不到 1 秒' : ms < 60_000 ? `${Math.round(ms / 1000)} 秒` : `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`);
const shortTitle = (t) => t.split(/[：:]/)[0].replace(/^一个/, '');
const checkName = (n) => (n.startsWith('action:') ? `按键 ${n.slice(7)}` : CHECK_NAMES[n] || n);
const playUrl = (g) => `games/${g.id}/play/index.html`;
const gameUrl = (g) => `game.html?id=${encodeURIComponent(g.id)}`;

/** Splits a run's event log into its steps, each with the events that happened during it. */
function segments(run) {
  const out = [];
  let current = null;
  for (const e of run.events) {
    if (e.type === 'STEP_STARTED') {
      current = { type: e.payload.type, trigger: e.payload.trigger, start: e.ts, end: e.ts, stepId: e.payload.stepId, events: [] };
      out.push(current);
    } else if (current) {
      current.events.push(e);
    }
    if (current) current.end = e.ts;
  }
  for (let i = 0; i < out.length; i++) {
    if (i + 1 < out.length) out[i].end = out[i + 1].start;
    out[i].ms = new Date(out[i].end) - new Date(out[i].start);
    const step = run.steps.find((s) => s.id === out[i].stepId);
    out[i].ok = step ? step.status === 'DONE' && step.ok !== false : true;
  }
  return out;
}

function analyse(game) {
  const events = game.runs.flatMap((r) => r.events);
  const reviews = events.filter((e) => e.type === 'REVIEW_RESULT');
  const blockers = reviews.flatMap((e) => (e.payload.issues || []).filter((i) => i.severity === 'BLOCKER'));
  const playtests = events.filter((e) => e.type === 'PLAYTEST_RESULT');
  const checks = playtests[playtests.length - 1]?.payload.checks || [];
  const tokens = events.filter((e) => e.type === 'USAGE')
    .reduce((n, e) => n + Number(e.payload.inputTokens || 0) + Number(e.payload.outputTokens || 0), 0);
  const first = events.find((e) => e.type === 'RUN_STARTED');
  const last = [...events].reverse().find((e) => e.type === 'RUN_FINISHED');
  const totalMs = first && last ? new Date(last.ts) - new Date(first.ts) : 0;
  return { reviews, blockers, playtests, checks, tokens, totalMs };
}

/** Thumbnail: the canvas shot taken during the playtest, or a quiet placeholder. */
function thumb(game, cls = 'thumb') {
  const box = el('div', { class: cls });
  const img = el('img', { src: `games/${game.id}/play/cover.png`, alt: '', loading: 'lazy' });
  img.addEventListener('error', () => img.replaceWith(el('span', { class: 'ph' }, shortTitle(game.title).slice(0, 1))));
  box.append(img);
  return box;
}

async function loadData() {
  for (const a of document.querySelectorAll('.repo-link')) a.href = REPO_URL;
  for (const a of document.querySelectorAll('.readme-link')) a.href = `${REPO_URL}#readme`;
  return (await fetch('data/games.json')).json();
}
