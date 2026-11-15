// Game page (game.html?id=...): big player plus how the game was generated. Helpers come from common.js.

function stepper(run) {
  return stepsOf(run).map((s, i) => el('li', { class: `step ${s.ok ? 'ok' : 'bad'}` },
    el('span', { class: 'step-num' }, s.ok ? String(i + 1) : '!'),
    el('div', { class: 'step-body' },
      el('b', {}, s.name),
      el('span', { class: 'step-actor' }, s.actor),
      s.note ? el('span', { class: `step-note ${s.ok ? '' : 'bad'}` }, s.note) : null,
      el('span', { class: 'step-time' }, duration(s.ms)))));
}

function reviewsView(game) {
  const { reviews } = analyse(game);
  if (!reviews.length) return [el('p', { class: 'muted' }, '没有审查记录')];
  return reviews.map((r, i) => {
    const issues = r.payload.issues || [];
    const box = el('div', { class: 'round-box' },
      el('div', { class: 'round' }, `第 ${i + 1} 次审查`, el('span', { class: issues.length ? 'bad-text' : 'ok-text' }, issues.length ? ` · ${issues.length} 个问题` : ' · 通过')));
    if (issues.length) {
      const ul = el('ul', { class: 'issues' });
      for (const it of issues) {
        const blocker = it.severity === 'BLOCKER';
        ul.append(el('li', {},
          el('div', { class: 'issue-head' },
            el('span', { class: `tag ${blocker ? 'blocker' : 'suggestion'}` }, blocker ? '阻塞' : '建议'),
            it.file ? el('span', { class: 'where' }, it.line != null ? `${it.file}:${it.line}` : it.file) : null),
          el('div', { class: 'issue-msg' }, it.message)));
      }
      box.append(ul);
    }
    return box;
  });
}

function playtestsView(game) {
  const { playtests } = analyse(game);
  if (!playtests.length) return [el('p', { class: 'muted' }, '没有试玩记录')];
  return playtests.map((p, i) => {
    const ul = el('ul', { class: 'checks' });
    for (const c of p.payload.checks || []) {
      ul.append(el('li', { class: c.passed ? 'pass' : 'fail' },
        el('span', { class: 'mark' }, c.passed ? '✓' : '✗'), checkName(c.name),
        !c.passed && c.detail ? el('span', { class: 'detail' }, c.detail) : null));
    }
    return el('div', { class: 'round-box' },
      el('div', { class: 'round' }, `第 ${i + 1} 次试玩`, el('span', { class: p.payload.passed ? 'ok-text' : 'bad-text' }, p.payload.passed ? ' · 通过' : ' · 未通过')),
      ul);
  });
}

function mountGame(game) {
  const stage = document.getElementById('stage');
  const load = () => {
    const frame = el('iframe', { src: playUrl(game), sandbox: SANDBOX, title: shortTitle(game.title), allow: 'fullscreen' });
    // Give the game keyboard focus as soon as it loads, so arrow keys work without an extra click.
    frame.addEventListener('load', () => { try { frame.contentWindow.focus(); } catch { /* ignore */ } });
    stage.replaceChildren(frame);
  };
  load();
  document.getElementById('reload').addEventListener('click', load);
  document.getElementById('fullscreen').addEventListener('click', () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else stage.requestFullscreen?.();
  });
}

async function main() {
  const data = await loadData();
  const id = new URLSearchParams(location.search).get('id');
  const game = data.games.find((g) => g.id === id) || data.games[data.games.length - 1];
  if (!game) throw new Error('没有找到这个游戏');

  const a = analyse(game);
  const passed = a.checks.filter((c) => c.passed).length;
  document.title = `${shortTitle(game.title)} · PlayForge`;
  document.getElementById('game-avatar').replaceWith(Object.assign(avatar(game), { id: 'game-avatar' }));
  document.getElementById('game-title').textContent = shortTitle(game.title);
  document.getElementById('game-ver').textContent = `版本 ${game.runs.length}`;
  document.getElementById('game-prompt').textContent = `需求：${game.prompt}`;
  document.getElementById('game-stats').replaceChildren(
    el('span', { class: 'ok-text' }, `★ AI 试玩 ${passed}/${a.checks.length} 项通过`),
    el('span', {}, `用时 ${duration(a.totalMs)}`),
    el('span', { class: a.blockers.length ? 'bad-text' : '' }, a.blockers.length ? `审查抓到并修复 ${a.blockers.length} 个 bug` : '审查一次通过'),
    a.tokens ? el('span', {}, `${Math.round(a.tokens / 1000)}k tokens`) : null,
    el('span', { class: 'by' }, 'Claude × Codex'));

  mountGame(game);

  const run = game.runs[game.runs.length - 1];
  document.getElementById('total').textContent = `· 共 ${duration(a.totalMs)}`;
  document.getElementById('stepper').replaceChildren(...stepper(run));
  document.getElementById('reviews').replaceChildren(...reviewsView(game));
  document.getElementById('playtests').replaceChildren(...playtestsView(game));
  const texts = run.events.filter((e) => e.type === 'AGENT_TEXT' && e.source === 'claude');
  if (texts.length) {
    document.getElementById('summary').textContent = texts[texts.length - 1].payload.text;
    document.getElementById('summary-box').hidden = false;
  }
}

main().catch((e) => {
  document.querySelector('.game-main').replaceChildren(el('p', { class: 'muted' }, `加载失败：${e.message}`), el('a', { href: './' }, '← 返回游戏广场'));
});
