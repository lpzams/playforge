// Game page (game.html?id=...): the player, then the generation told as a short story. Helpers come from common.js.

const ACTORS = {
  CODE: ['Claude', 'claude', 'C'],
  FIX: ['Claude', 'claude', 'C'],
  REVIEW: ['Codex', 'codex', 'X'],
  PLAYTEST: ['自动试玩', 'play', '试'],
  COMMIT: ['Git', 'git', 'G'],
};

/** One sentence (plus details) per step, in plain language. */
function entry(seg, reviewNo) {
  const [name, cls, mono] = ACTORS[seg.type] || [seg.type, '', '·'];
  const body = [];
  const said = (text, bad = false) => body.push(el('p', { class: `entry-text${bad ? ' bad' : ''}` }, text));

  if (seg.type === 'CODE') {
    const files = new Set(seg.events.filter((e) => e.type === 'FILE_CHANGED').map((e) => e.payload.path)).size;
    said(files ? `根据需求写出了第一版，改动 ${files} 个文件。` : '根据需求写出了第一版。');
  } else if (seg.type === 'FIX') {
    said(seg.trigger === 'PLAYTEST' ? '按试玩报告修复了问题。' : '按审查意见修改了代码。');
  } else if (seg.type === 'REVIEW') {
    const result = seg.events.find((e) => e.type === 'REVIEW_RESULT');
    const issues = result?.payload.issues || [];
    const blockers = issues.filter((i) => i.severity === 'BLOCKER');
    if (!issues.length) said(reviewNo > 1 ? '复查了修改，没有再发现问题。' : '审查通过，没有发现问题。');
    else said(blockers.length ? `指出 ${blockers.length} 个必须修改的问题：` : `提了 ${issues.length} 条改进建议：`);
    for (const it of issues) {
      const loc = it.file ? (it.line != null ? `${it.file}:${it.line}` : it.file) : null;
      body.push(el('div', { class: 'quote' },
        loc ? el('span', { class: 'loc' }, `${it.severity === 'BLOCKER' ? '' : '建议 · '}${loc}`) : null,
        el('p', {}, it.message)));
    }
  } else if (seg.type === 'PLAYTEST') {
    const result = seg.events.find((e) => e.type === 'PLAYTEST_RESULT');
    const checks = result?.payload.checks || [];
    if (result?.payload.passed) {
      said(`在浏览器里试玩了一遍，${checks.length} 项检查全部通过。`);
      body.push(el('p', { class: 'checklist' }, checks.map((c) => checkName(c.name)).join(' · ')));
    } else {
      said('试玩没有通过：', true);
      const ul = el('ul', { class: 'failed' });
      for (const c of checks.filter((x) => !x.passed)) ul.append(el('li', {}, checkName(c.name), c.detail ? el('span', {}, c.detail) : null));
      body.push(ul);
    }
  } else if (seg.type === 'COMMIT') {
    said('提交代码，游戏上线。');
  }

  return el('li', { class: 'entry' },
    el('div', { class: `who ${cls}` }, mono),
    el('div', {},
      el('div', { class: 'entry-head' }, el('span', { class: 'name' }, name), el('span', {}, duration(seg.ms))),
      ...body));
}

function story(game) {
  const items = [];
  for (const run of game.runs) {
    let reviews = 0;
    for (const seg of segments(run)) {
      if (seg.type === 'REVIEW') reviews++;
      items.push(entry(seg, reviews));
    }
    const texts = run.events.filter((e) => e.type === 'AGENT_TEXT' && e.source === 'claude');
    if (texts.length) {
      const note = el('div', { class: 'note' }, texts[texts.length - 1].payload.text);
      note.hidden = true;
      const toggle = el('button', { class: 'pill plain more', type: 'button' }, '查看 Claude 的说明');
      toggle.addEventListener('click', () => {
        note.hidden = !note.hidden;
        toggle.textContent = note.hidden ? '查看 Claude 的说明' : '收起说明';
      });
      items.push(el('li', { class: 'entry' },
        el('div', { class: 'who claude' }, 'C'),
        el('div', {}, el('div', { class: 'entry-head' }, el('span', { class: 'name' }, 'Claude'), el('span', {}, '交付说明')), toggle, note)));
    }
  }
  return items;
}

const PAD = 10; // px of the game's own background kept around its canvas

/**
 * Plays the game scaled to fill the stage. The iframe is rendered at the canvas size (plus a small pad) and scaled
 * with a transform, so a 480x320 game fills a large stage instead of sitting small inside a black letterbox.
 * Without a known size the iframe simply fills the stage.
 */
function mountGame(game, size) {
  const stage = document.getElementById('stage');
  const col = stage.parentElement;
  let frame = null;
  const w = size ? size.width + PAD * 2 : 0;
  const h = size ? size.height + PAD * 2 : 0;

  const layout = () => {
    if (!frame || !size) return;
    if (document.fullscreenElement === stage) {
      stage.style.width = ''; stage.style.height = '';
    } else if (matchMedia('(max-width: 1100px)').matches) {
      stage.style.width = '100%'; stage.style.height = `${Math.round(col.clientWidth * h / w)}px`;
    } else {
      const maxH = Math.max(320, innerHeight - 160);
      const k = Math.min(col.clientWidth / w, maxH / h);
      stage.style.width = `${Math.round(w * k)}px`; stage.style.height = `${Math.round(h * k)}px`;
    }
    const k = Math.min(stage.clientWidth / w, stage.clientHeight / h);
    frame.style.transform = `translate(-50%, -50%) scale(${k})`;
  };

  const load = () => {
    frame = el('iframe', { src: playUrl(game), sandbox: SANDBOX, title: shortTitle(game.title), allow: 'fullscreen' });
    if (size) { frame.style.width = `${w}px`; frame.style.height = `${h}px`; } else stage.classList.add('fill');
    // Give the game keyboard focus once it loads, so the arrow keys work without an extra click.
    frame.addEventListener('load', () => { try { frame.contentWindow.focus(); } catch { /* ignore */ } });
    stage.replaceChildren(frame);
    layout();
  };

  load();
  new ResizeObserver(layout).observe(col);
  addEventListener('resize', layout);
  document.addEventListener('fullscreenchange', () => requestAnimationFrame(layout));
  document.getElementById('reload').addEventListener('click', load);
  document.getElementById('fullscreen').addEventListener('click', () => {
    if (document.fullscreenElement) document.exitFullscreen();
    else stage.requestFullscreen?.();
  });
}

async function canvasSize(game) {
  try {
    const s = await (await fetch(`games/${game.id}/play/cover.json`)).json();
    return s.width > 0 && s.height > 0 ? s : null;
  } catch {
    return null;
  }
}

async function main() {
  const data = await loadData();
  const id = new URLSearchParams(location.search).get('id');
  const game = data.games.find((g) => g.id === id) || data.games[data.games.length - 1];
  if (!game) throw new Error('没有找到这个游戏');

  const a = analyse(game);
  document.title = `${shortTitle(game.title)} · PlayForge`;
  document.getElementById('game-title').textContent = shortTitle(game.title);
  document.getElementById('game-meta').textContent = [
    `用时 ${duration(a.totalMs)}`,
    a.blockers.length ? `审查中改掉 ${a.blockers.length} 个问题` : '审查一次通过',
    `试玩 ${a.checks.filter((c) => c.passed).length}/${a.checks.length} 项通过`,
  ].join(' · ');
  document.getElementById('game-prompt').textContent = game.prompt;
  mountGame(game, await canvasSize(game));

  document.getElementById('story-sub').textContent = `从一句话到能玩，一共 ${duration(a.totalMs)}。`;
  document.getElementById('feed').replaceChildren(...story(game));
}

main().catch((e) => {
  document.querySelector('.game-main').replaceChildren(el('p', { class: 'muted' }, `加载失败：${e.message}`), el('a', { href: './' }, '返回游戏广场'));
});
