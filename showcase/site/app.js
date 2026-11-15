// Renders data/games.json as an "app plaza". All text from the data (AI output included) is set with textContent only.
const REPO_URL = document.querySelector('meta[name="repo-url"]')?.content || 'https://github.com/';
const SANDBOX = 'allow-scripts allow-pointer-lock';

const STEP_NAMES = { CODE: 'Claude 写代码', REVIEW: 'Codex 审查', FIX: 'Claude 修复', PLAYTEST: 'AI 试玩', COMMIT: '提交' };
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

function stepsOf(run) {
  const starts = run.events.filter((e) => e.type === 'STEP_STARTED');
  const finished = run.events.find((e) => e.type === 'RUN_FINISHED');
  return starts.map((s, i) => {
    const end = i + 1 < starts.length ? starts[i + 1].ts : finished?.ts ?? s.ts;
    const step = run.steps.find((x) => x.id === s.payload.stepId);
    let name = STEP_NAMES[s.payload.type] || s.payload.type;
    if (s.payload.type === 'FIX') name += s.payload.trigger === 'PLAYTEST' ? '（试玩发现的问题）' : '（审查意见）';
    let note = '';
    if (s.payload.type === 'REVIEW' && step) note = step.ok === false ? '发现阻塞问题' : '通过';
    if (s.payload.type === 'PLAYTEST' && step) note = step.ok === false ? '未通过' : '通过';
    const ok = step ? step.status === 'DONE' && step.ok !== false : true;
    return { name, note, ms: new Date(end) - new Date(s.ts), ok };
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

function card(game) {
  const a = analyse(game);
  const passed = a.checks.filter((c) => c.passed).length;
  const node = el('button', { class: 'card', type: 'button' },
    el('div', { class: 'card-top' },
      avatar(game),
      el('div', { class: 'title-box' },
        el('div', { class: 'title' }, shortTitle(game.title)),
        el('span', { class: 'ver' }, `版本 ${game.runs.length}`))),
    el('p', { class: 'desc' }, game.prompt),
    el('div', { class: 'author' },
      el('span', { class: 'faces' }, el('span', { class: 'face claude' }, 'C'), el('span', { class: 'face codex' }, 'X')),
      'Claude × Codex'),
    el('div', { class: 'stats' },
      el('div', { class: 'rating' }, el('span', { class: 'star' }, '★ '), `AI 试玩 ${passed}/${a.checks.length} 项通过`),
      el('div', { class: 'usage' },
        el('span', {}, `用时 ${duration(a.totalMs)}`),
        el('span', {}, a.blockers.length ? `修复 ${a.blockers.length} 个 bug` : '审查一次通过'),
        el('span', { class: 'pill' }, '可试玩'))));
  node.addEventListener('click', () => openModal(game));
  return node;
}

function processView(game) {
  const box = document.createDocumentFragment();
  for (const run of game.runs) {
    const a = analyse({ runs: [run] });

    const tl = el('ol', { class: 'timeline' });
    for (const s of stepsOf(run)) {
      tl.append(el('li', { class: s.ok ? '' : 'bad' }, s.name, s.note ? `：${s.note}` : '', el('span', { class: 't' }, duration(s.ms))));
    }
    box.append(el('div', {}, el('div', { class: 'block-title' }, `生成过程 · 共 ${duration(a.totalMs)}`), tl));

    if (a.reviews.some((r) => (r.payload.issues || []).length)) {
      const list = el('div');
      a.reviews.forEach((r, i) => {
        const issues = r.payload.issues || [];
        list.append(el('div', { class: 'round' }, `第 ${i + 1} 次审查：${issues.length ? `${issues.length} 个问题` : '通过'}`));
        if (!issues.length) return;
        const ul = el('ul', { class: 'issues' });
        for (const it of issues) {
          const blocker = it.severity === 'BLOCKER';
          ul.append(el('li', {},
            el('span', { class: `tag ${blocker ? 'blocker' : 'suggestion'}` }, blocker ? '阻塞' : '建议'),
            it.file ? el('span', { class: 'where' }, it.line != null ? `${it.file}:${it.line}` : it.file) : null,
            it.message));
        }
        list.append(ul);
      });
      box.append(el('div', {}, el('div', { class: 'block-title' }, 'Codex 审查发现'), list));
    }

    if (a.playtests.length) {
      const list = el('div');
      a.playtests.forEach((p, i) => {
        list.append(el('div', { class: 'round' }, `第 ${i + 1} 次试玩：${p.payload.passed ? '通过' : '未通过'}`));
        const ul = el('ul', { class: 'checks' });
        for (const c of p.payload.checks || []) {
          ul.append(el('li', { class: c.passed ? 'pass' : 'fail' }, checkName(c.name),
            !c.passed && c.detail ? el('span', { class: 'detail' }, c.detail) : null));
        }
        list.append(ul);
      });
      box.append(el('div', {}, el('div', { class: 'block-title' }, 'AI 试玩'), list));
    }

    const texts = run.events.filter((e) => e.type === 'AGENT_TEXT' && e.source === 'claude');
    const summary = texts[texts.length - 1];
    if (summary) {
      box.append(el('details', { class: 'summary-box' }, el('summary', {}, 'Claude 的最终说明'), el('div', { class: 'summary' }, summary.payload.text)));
    }
  }
  return box;
}

const modal = document.getElementById('modal');

function openModal(game) {
  document.getElementById('modal-avatar').replaceWith(Object.assign(avatar(game, 'avatar sm'), { id: 'modal-avatar' }));
  document.getElementById('modal-title').textContent = shortTitle(game.title);
  document.getElementById('modal-ver').textContent = `版本 ${game.runs.length}`;
  document.getElementById('modal-prompt').textContent = `需求：${game.prompt}`;
  document.getElementById('modal-stage').replaceChildren(el('iframe', { src: playUrl(game), sandbox: SANDBOX, title: shortTitle(game.title) }));
  document.getElementById('modal-process').replaceChildren(processView(game));
  modal.hidden = false;
  document.body.style.overflow = 'hidden';
}

function closeModal() {
  modal.hidden = true;
  document.getElementById('modal-stage').replaceChildren();
  document.body.style.overflow = '';
}

modal.addEventListener('click', (e) => { if (e.target.closest('[data-close]')) closeModal(); });
document.addEventListener('keydown', (e) => { if (e.key === 'Escape' && !modal.hidden) closeModal(); });

const state = { games: [], query: '', sort: 'newest', bugOnly: false, view: 'grid' };

function render() {
  const q = state.query.trim().toLowerCase();
  let list = state.games.filter((g) => !q || g.title.toLowerCase().includes(q) || g.prompt.toLowerCase().includes(q));
  if (state.bugOnly) list = list.filter((g) => analyse(g).blockers.length > 0);
  list = state.sort === 'fastest'
    ? [...list].sort((a, b) => analyse(a).totalMs - analyse(b).totalMs)
    : [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  const grid = document.getElementById('game-list');
  grid.className = state.view === 'list' ? 'cards list' : 'cards';
  grid.replaceChildren(...list.map(card));
  document.getElementById('count').textContent = String(list.length);
  document.getElementById('empty').hidden = list.length > 0;
  document.getElementById('sort').textContent = state.sort === 'fastest' ? '⇅ 用时最短' : '⇅ 最新生成';
  document.getElementById('sort-label').textContent = state.sort === 'fastest' ? '按生成用时排序' : '按生成时间排序';
  document.getElementById('filter-bug').setAttribute('aria-pressed', String(state.bugOnly));
  document.getElementById('view-grid').classList.toggle('active', state.view === 'grid');
  document.getElementById('view-list').classList.toggle('active', state.view === 'list');
}

async function main() {
  for (const a of document.querySelectorAll('.repo-link')) a.href = REPO_URL;
  for (const a of document.querySelectorAll('.readme-link')) a.href = `${REPO_URL}#readme`;
  document.getElementById('search').addEventListener('input', (e) => { state.query = e.target.value; render(); });
  document.getElementById('sort').addEventListener('click', () => { state.sort = state.sort === 'newest' ? 'fastest' : 'newest'; render(); });
  document.getElementById('filter-bug').addEventListener('click', () => { state.bugOnly = !state.bugOnly; render(); });
  document.getElementById('view-grid').addEventListener('click', () => { state.view = 'grid'; render(); });
  document.getElementById('view-list').addEventListener('click', () => { state.view = 'list'; render(); });

  const data = await (await fetch('data/games.json')).json();
  state.games = data.games;
  document.getElementById('exported-at').textContent = new Date(data.exportedAt).toLocaleString('zh-CN', { hour12: false });
  render();
}

main().catch((e) => {
  document.getElementById('game-list').append(el('p', {}, `加载失败：${e.message}`));
});
