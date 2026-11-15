// Renders data/games.json. All text from the data (AI output included) is set with textContent only.
const REPO_URL = document.querySelector('meta[name="repo-url"]')?.content || 'https://github.com/';

const STEP_NAMES = { CODE: '写代码', REVIEW: '审查', FIX: '修复', PLAYTEST: 'AI 试玩', COMMIT: '提交' };
const CHECK_NAMES = {
  build: '构建', load: '加载', probe: '探针', not_blank: '画面非空白', no_errors: '无报错', restart: '重新开始', harness: '试玩脚本',
};

function el(tag, attrs = {}, ...children) {
  const node = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (k === 'class') node.className = v;
    else node.setAttribute(k, v);
  }
  for (const c of children) if (c != null) node.append(c instanceof Node ? c : document.createTextNode(String(c)));
  return node;
}

const seconds = (ms) => (ms < 1000 ? '<1 秒' : ms < 60_000 ? `${Math.round(ms / 1000)} 秒` : `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`);
const shortTitle = (t) => t.split(/[：:]/)[0];
const checkName = (n) => (n.startsWith('action:') ? `按键 ${n.slice(7)}` : CHECK_NAMES[n] || n);

/** Step list with durations, derived from STEP_STARTED timestamps of one run's event log. */
function stepsOf(run) {
  const events = run.events;
  const starts = events.filter((e) => e.type === 'STEP_STARTED');
  const finished = events.find((e) => e.type === 'RUN_FINISHED');
  return starts.map((s, i) => {
    const end = i + 1 < starts.length ? starts[i + 1].ts : finished?.ts ?? s.ts;
    const step = run.steps.find((x) => x.id === s.payload.stepId);
    let name = STEP_NAMES[s.payload.type] || s.payload.type;
    if (s.payload.type === 'FIX') name += s.payload.trigger === 'PLAYTEST' ? '（试玩问题）' : '（审查意见）';
    const ok = step ? step.status === 'DONE' && step.ok !== false : true;
    return { name, ms: new Date(end) - new Date(s.ts), ok };
  });
}

function analyse(game) {
  const events = game.runs.flatMap((r) => r.events);
  const reviews = events.filter((e) => e.type === 'REVIEW_RESULT');
  const blockers = reviews.flatMap((e) => (e.payload.issues || []).filter((i) => i.severity === 'BLOCKER'));
  const playtests = events.filter((e) => e.type === 'PLAYTEST_RESULT');
  const lastPlaytest = playtests[playtests.length - 1];
  const checks = lastPlaytest?.payload.checks || [];
  const tokens = events.filter((e) => e.type === 'USAGE')
    .reduce((n, e) => n + Number(e.payload.inputTokens || 0) + Number(e.payload.outputTokens || 0), 0);
  const first = events.find((e) => e.type === 'RUN_STARTED');
  const last = [...events].reverse().find((e) => e.type === 'RUN_FINISHED');
  const totalMs = first && last ? new Date(last.ts) - new Date(first.ts) : 0;
  return { reviews, blockers, playtests, checks, tokens, totalMs };
}

function processView(game) {
  const box = el('div', { class: 'process' });
  for (const run of game.runs) {
    const a = analyse({ runs: [run] });

    const steps = el('ul', { class: 'steps' });
    for (const s of stepsOf(run)) steps.append(el('li', { class: s.ok ? 'ok' : 'bad' }, s.name, el('small', {}, seconds(s.ms))));
    box.append(el('div', {}, el('div', { class: 'block-title' }, '步骤与耗时'), steps));

    if (a.reviews.length) {
      const list = el('div');
      a.reviews.forEach((r, i) => {
        const issues = r.payload.issues || [];
        list.append(el('div', { class: 'round' }, `第 ${i + 1} 次审查：${issues.length ? `${issues.length} 个问题` : '通过'}`));
        if (issues.length) {
          const ul = el('ul', { class: 'issues' });
          for (const it of issues) {
            const blocker = it.severity === 'BLOCKER';
            ul.append(el('li', {},
              el('span', { class: `tag ${blocker ? 'blocker' : 'suggestion'}` }, blocker ? '阻塞' : '建议'),
              it.file ? el('span', { class: 'where' }, it.line != null ? `${it.file}:${it.line}` : it.file) : null,
              it.message));
          }
          list.append(ul);
        }
      });
      box.append(el('div', {}, el('div', { class: 'block-title' }, 'Codex 审查'), list));
    }

    if (a.playtests.length) {
      const list = el('div');
      a.playtests.forEach((p, i) => {
        list.append(el('div', { class: 'round' }, `第 ${i + 1} 次试玩：${p.payload.passed ? '通过' : '未通过'}`));
        const ul = el('ul', { class: 'checks' });
        for (const c of p.payload.checks || []) {
          ul.append(el('li', { class: c.passed ? 'ok' : 'bad' }, checkName(c.name), c.detail ? el('span', { class: 'detail' }, c.detail) : null));
        }
        list.append(ul);
      });
      box.append(el('div', {}, el('div', { class: 'block-title' }, 'AI 试玩'), list));
    }

    const claudeTexts = run.events.filter((e) => e.type === 'AGENT_TEXT' && e.source === 'claude');
    const summary = claudeTexts[claudeTexts.length - 1];
    if (summary) box.append(el('div', {}, el('div', { class: 'block-title' }, 'Claude 的最终说明'), el('div', { class: 'summary' }, summary.payload.text)));
  }
  return box;
}

function gameCard(game) {
  const a = analyse(game);
  const passed = a.checks.filter((c) => c.passed).length;
  const card = el('article', { class: 'game' });
  const chips = el('div', { class: 'chips' },
    el('span', { class: 'chip' }, `用时 ${seconds(a.totalMs)}`),
    el('span', { class: `chip ${a.blockers.length ? 'bad' : ''}` }, `审查发现 ${a.blockers.length} 个阻塞问题`),
    el('span', { class: 'chip ok' }, `试玩 ${passed}/${a.checks.length} 项通过`),
    a.tokens ? el('span', { class: 'chip' }, `${Math.round(a.tokens / 1000)}k tokens`) : null);
  card.append(el('div', { class: 'game-head' }, el('span', { class: 'game-title' }, shortTitle(game.title)), chips));
  card.append(el('div', { class: 'prompt' }, `需求：${game.prompt}`));

  const playBtn = el('button', { class: 'btn primary', type: 'button' }, '开始玩');
  const processBtn = el('button', { class: 'btn', type: 'button' }, '生成过程');
  card.append(el('div', { class: 'actions' }, playBtn, processBtn));

  let stage = null;
  playBtn.addEventListener('click', () => {
    if (stage) { stage.remove(); stage = null; playBtn.textContent = '开始玩'; return; }
    stage = el('div', { class: 'stage' },
      el('iframe', { src: `games/${game.id}/play/index.html`, sandbox: 'allow-scripts allow-pointer-lock', title: shortTitle(game.title) }));
    processBtn.parentNode.after(stage);
    playBtn.textContent = '收起游戏';
  });

  let process = null;
  processBtn.addEventListener('click', () => {
    if (process) { process.remove(); process = null; processBtn.textContent = '生成过程'; return; }
    process = processView(game);
    card.append(process);
    processBtn.textContent = '收起过程';
  });
  return card;
}

function stats(games) {
  const all = games.map((g) => analyse(g));
  const blockers = all.reduce((n, a) => n + a.blockers.length, 0);
  const avg = all.length ? all.reduce((n, a) => n + a.totalMs, 0) / all.length : 0;
  const box = document.getElementById('stats');
  const stat = (value, label) => el('div', { class: 'stat' }, el('b', {}, value), el('span', {}, label));
  box.append(
    stat(String(games.length), '个可玩的游戏'),
    stat(String(blockers), '个真实 bug 被 Codex 审查抓到'),
    stat(seconds(avg), '平均生成用时'),
  );
}

async function main() {
  document.getElementById('repo-link').href = REPO_URL;
  const data = await (await fetch('data/games.json')).json();
  document.getElementById('exported-at').textContent = new Date(data.exportedAt).toLocaleString('zh-CN', { hour12: false });
  stats(data.games);
  const list = document.getElementById('game-list');
  for (const g of [...data.games].reverse()) list.append(gameCard(g));
}

main().catch((e) => {
  document.getElementById('game-list').append(el('p', { class: 'muted' }, `加载失败：${e.message}`));
});
