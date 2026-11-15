// Shared by the plaza (index.html) and the game page (game.html).
// All text from the data (AI output included) is set with textContent only.
const REPO_URL = document.querySelector('meta[name="repo-url"]')?.content || 'https://github.com/';
const SANDBOX = 'allow-scripts allow-pointer-lock';

const STEP_NAMES = { CODE: '写代码', REVIEW: '审查', FIX: '修复', PLAYTEST: 'AI 试玩', COMMIT: '提交' };
const STEP_ACTORS = { CODE: 'Claude', REVIEW: 'Codex', FIX: 'Claude', PLAYTEST: 'Playwright', COMMIT: 'git' };
const CHECK_NAMES = {
  build: '构建', load: '加载', probe: '探针', not_blank: '画面非空白', no_errors: '无报错', restart: '重新开始', harness: '试玩脚本',
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

const duration = (ms) => (ms < 1000 ? '<1 秒' : ms < 60_000 ? `${Math.round(ms / 1000)} 秒` : `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`);
const shortTitle = (t) => t.split(/[：:]/)[0].replace(/^一个/, '');
const checkName = (n) => (n.startsWith('action:') ? `按键 ${n.slice(7)}` : CHECK_NAMES[n] || n);
const playUrl = (g) => `games/${g.id}/play/index.html`;
const gameUrl = (g) => `game.html?id=${encodeURIComponent(g.id)}`;

function stepsOf(run) {
  const starts = run.events.filter((e) => e.type === 'STEP_STARTED');
  const finished = run.events.find((e) => e.type === 'RUN_FINISHED');
  return starts.map((s, i) => {
    const end = i + 1 < starts.length ? starts[i + 1].ts : finished?.ts ?? s.ts;
    const step = run.steps.find((x) => x.id === s.payload.stepId);
    const type = s.payload.type;
    let note = '';
    if (type === 'REVIEW' && step) note = step.ok === false ? '发现问题' : '通过';
    if (type === 'PLAYTEST' && step) note = step.ok === false ? '未通过' : '通过';
    if (type === 'FIX') note = s.payload.trigger === 'PLAYTEST' ? '按试玩报告' : '按审查意见';
    const ok = step ? step.status === 'DONE' && step.ok !== false : true;
    return { type, name: STEP_NAMES[type] || type, actor: STEP_ACTORS[type] || '', note, ms: new Date(end) - new Date(s.ts), ok };
  });
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

/** Round avatar: the game's cover (canvas shot from the playtest), or its first character on a colour. */
function avatar(game, cls = 'avatar') {
  const box = el('div', { class: cls });
  let h = 0;
  for (const c of game.id) h = (h * 31 + c.charCodeAt(0)) % 360;
  const letter = () => {
    box.replaceChildren(shortTitle(game.title).slice(0, 1));
    box.style.background = `linear-gradient(135deg, hsl(${h} 65% 48%), hsl(${(h + 45) % 360} 65% 38%))`;
  };
  const img = el('img', { src: `games/${game.id}/play/cover.png`, alt: '', loading: 'lazy' });
  img.addEventListener('error', letter);
  box.append(img);
  return box;
}

async function loadData() {
  for (const a of document.querySelectorAll('.repo-link')) a.href = REPO_URL;
  for (const a of document.querySelectorAll('.readme-link')) a.href = `${REPO_URL}#readme`;
  return (await fetch('data/games.json')).json();
}
