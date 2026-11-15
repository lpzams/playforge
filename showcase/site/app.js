// Renders data/games.json. All text from the data (AI output included) is set with textContent only.
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

function hue(id) {
  let h = 0;
  for (const c of id) h = (h * 31 + c.charCodeAt(0)) % 360;
  return h;
}

/** Cover image from the playtest's canvas shot; a coloured placeholder until one exists. */
function cover(game) {
  const box = el('div', { class: 'cover' });
  const h = hue(game.id);
  const fallback = el('div', { class: 'fallback' }, shortTitle(game.title).slice(0, 1));
  fallback.style.background = `linear-gradient(135deg, hsl(${h} 70% 45%), hsl(${(h + 50) % 360} 70% 35%))`;
  const img = el('img', { src: `games/${game.id}/play/cover.png`, alt: `${shortTitle(game.title)} 截图`, loading: 'lazy' });
  img.addEventListener('error', () => img.replaceWith(fallback));
  box.append(img, el('span', { class: 'play' }, '▶ 试玩 & 生成过程'));
  return box;
}

function card(game) {
  const a = analyse(game);
  const passed = a.checks.filter((c) => c.passed).length;
  const node = el('button', { class: 'card', type: 'button' },
    cover(game),
    el('div', { class: 'card-body' },
      el('div', { class: 'card-title' }, el('b', {}, shortTitle(game.title)), el('span', { class: 'ver' }, `第 ${game.runs.length} 版`)),
      el('p', { class: 'desc' }, game.prompt),
      el('div', { class: 'by' }, '🤖 Claude 写 · Codex 审 · AI 试玩'),
      el('div', { class: 'card-foot' },
        el('span', { class: 'ok' }, `✓ 试玩 ${passed}/${a.checks.length}`),
        a.blockers.length ? el('span', { class: 'bug' }, `🐞 审查抓到 ${a.blockers.length} 个 bug`) : el('span', {}, '审查一次通过'),
        el('span', {}, `⏱ ${duration(a.totalMs)}`),
        a.tokens ? el('span', {}, `${Math.round(a.tokens / 1000)}k tokens`) : null)));
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
  document.getElementById('modal-title').textContent = shortTitle(game.title);
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

function livePlayer(games) {
  let i = 0;
  const show = () => {
    const g = games[i];
    document.getElementById('live-title').textContent = shortTitle(g.title);
    document.getElementById('live-stage').replaceChildren(el('iframe', { src: playUrl(g), sandbox: SANDBOX, title: shortTitle(g.title) }));
  };
  document.getElementById('live-prev').addEventListener('click', () => { i = (i - 1 + games.length) % games.length; show(); });
  document.getElementById('live-next').addEventListener('click', () => { i = (i + 1) % games.length; show(); });
  show();
}

function stats(games) {
  const all = games.map(analyse);
  const blockers = all.reduce((n, a) => n + a.blockers.length, 0);
  const avg = all.length ? all.reduce((n, a) => n + a.totalMs, 0) / all.length : 0;
  const stat = (v, label) => el('div', { class: 'stat' }, el('b', {}, v), el('span', {}, label));
  document.getElementById('stats').replaceChildren(
    stat(String(games.length), '个可玩的游戏'),
    stat(String(blockers), '个 bug 被审查抓到'),
    stat(duration(avg).replace(' 秒', 's').replace(' 分 ', 'm'), '平均生成用时'));
}

async function main() {
  for (const a of document.querySelectorAll('.repo-link')) a.href = REPO_URL;
  const data = await (await fetch('data/games.json')).json();
  const games = [...data.games].reverse();
  document.getElementById('exported-at').textContent = new Date(data.exportedAt).toLocaleString('zh-CN', { hour12: false });
  document.getElementById('game-count').textContent = `共 ${games.length} 个`;
  stats(games);
  if (games.length) livePlayer(games);
  document.getElementById('game-list').replaceChildren(...games.map(card));
}

main().catch((e) => {
  document.getElementById('game-list').append(el('p', {}, `加载失败：${e.message}`));
});
