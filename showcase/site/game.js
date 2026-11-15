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
      for (const c of checks.filter((x) => !x.passed)) ul.append(el('li', {}, failText(c), c.detail ? el('span', {}, `（${checkName(c.name)}：${c.detail}）`) : null));
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

const KEY_NAMES = { Space: '空格', ArrowLeft: '←', ArrowRight: '→', ArrowUp: '↑', ArrowDown: '↓', Enter: '回车' };

/** A failed playtest check in plain Chinese. */
function failText(c) {
  if (c.name.startsWith('action:')) {
    const key = (c.detail || '').match(/pressing (\w+)/)?.[1];
    return key ? `按${KEY_NAMES[key] || key}键后，游戏没有任何反应` : `「${c.name.slice(7)}」操作没有效果`;
  }
  return {
    restart: '重新开始后，游戏没有回到初始状态', not_blank: '游戏画面是一片空白', load: '游戏页面打不开',
    no_errors: '运行时有报错', probe: '游戏没有接入测试探针', build: '代码构建失败',
  }[c.name] || `${checkName(c.name)}没有通过`;
}

const firstSentence = (s) => (s.match(/^[\s\S]*?[.。](?=\s|$)/)?.[0] || s).trim();

/**
 * What the platform caught: the same request handed straight to an AI (Claude stops at its first version) next to
 * what PlayForge did with that version — every review and playtest gate in order, and what each one stopped.
 */
function guard(game) {
  const events = game.runs.flatMap((r) => r.events);
  const firstText = events.find((e) => e.type === 'AGENT_TEXT' && e.source === 'claude');
  const gates = [];
  const problems = [];
  const seen = new Set();
  let fixes = 0;
  for (const run of game.runs) {
    for (const seg of segments(run)) {
      if (seg.type === 'FIX') fixes++;
      if (seg.type === 'REVIEW') {
        const issues = (seg.events.find((e) => e.type === 'REVIEW_RESULT')?.payload.issues || []).filter((i) => i.severity === 'BLOCKER');
        for (const i of issues) problems.push({ by: 'Codex 审查发现', text: firstSentence(i.message), loc: i.file ? `${i.file}${i.line != null ? ':' + i.line : ''}` : null });
        gates.push({ who: 'Codex 审查', blocked: issues.length > 0,
          text: issues.length ? `拦下 ${issues.length} 个必须修改的问题，打回给 Claude` : '通过' });
      } else if (seg.type === 'PLAYTEST') {
        const p = seg.events.find((e) => e.type === 'PLAYTEST_RESULT')?.payload;
        if (!p) continue;
        const failed = (p.checks || []).filter((c) => !c.passed);
        const again = failed.length > 0 && failed.every((c) => seen.has(c.name));
        for (const c of failed) if (!seen.has(c.name)) { seen.add(c.name); problems.push({ by: '自动试玩发现', text: failText(c) }); }
        gates.push({ who: '自动试玩', blocked: failed.length > 0,
          text: failed.length ? `${again ? '修完再试，问题还在，再次打回' : `拦下：${failed.map(failText).join('；')}，打回给 Claude`}`
            : `${(p.checks || []).length} 项检查全部通过，才允许提交上线` });
      }
    }
  }

  const said = firstText ? plain(firstText.payload.text.split('\n\n')[0]) : '第一版写好了。';
  const n = problems.length;
  const before = el('div', { class: 'cmp before' },
    el('div', { class: 'cmp-title' }, '直接让 AI 写'),
    el('p', { class: 'cmp-lead' }, 'Claude 交付第一版时说：'),
    el('blockquote', {}, said),
    el('p', { class: 'cmp-lead' }, n ? `到这里就结束了。这一版其实带着 ${n} 个问题：` : '到这里就结束了，没有人审查，也没有人真的玩过。'),
    n ? el('ul', { class: 'cmp-problems' }, ...problems.map((p) => el('li', {}, p.text, p.loc ? el('span', { class: 'loc' }, p.loc) : null))) : null,
    el('p', { class: 'cmp-result' }, n ? `结果：带着 ${n} 个问题交到你手上` : '结果：能不能玩，要你自己去试'));
  const after = el('div', { class: 'cmp after' },
    el('div', { class: 'cmp-title' }, '经过 PlayForge'),
    el('p', { class: 'cmp-lead' }, '同一个第一版，接着要过两道关，不过就自动打回修改：'),
    el('ol', { class: 'gates' }, ...gates.map((g) => el('li', { class: g.blocked ? 'blocked' : 'passed' },
      el('span', { class: 'gate-who' }, g.who), el('span', { class: 'gate-text' }, g.text)))),
    el('p', { class: 'cmp-result' }, [n ? `${n} 个问题在上线前修掉` : '两道关都一次通过', fixes ? `自动修复 ${fixes} 轮，没有人插手` : null,
      `全程 ${duration(analyse(game).totalMs)}`].filter(Boolean).join(' · ')));
  return [el('h2', {}, '平台拦下了什么'), el('p', { class: 'story-sub' }, '同一个需求：直接让 AI 写，和交给 PlayForge 写。'), el('div', { class: 'compare' }, before, after)];
}

const PAD = 10; // px of the game page background kept around its canvas (see hidpi.js)

/**
 * Plays the game as large as the page allows. The stage takes the canvas aspect ratio (plus a small pad) and the
 * iframe fills it; inside, hidpi.js sizes the canvas to the frame and renders it at full resolution, so the game is
 * sharp instead of a small bitmap stretched up.
 */
function mountGame(game, size) {
  const stage = document.getElementById('stage');
  if (size) document.querySelector('.game-layout').style.setProperty('--ar', ((size.width + PAD * 2) / (size.height + PAD * 2)).toFixed(4));

  const load = () => {
    const frame = el('iframe', { src: playUrl(game), sandbox: SANDBOX, title: shortTitle(game.title), allow: 'fullscreen' });
    // Give the game keyboard focus once it loads, so the arrow keys work without an extra click.
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
  const g = document.getElementById('guard');
  g.replaceChildren(...guard(game));
  g.hidden = false;

  document.getElementById('story-sub').textContent = `从一句话到能玩，一共 ${duration(a.totalMs)}。`;
  document.getElementById('feed').replaceChildren(...story(game));
  setupReplay(game, () => story(game));
}

main().catch((e) => {
  document.querySelector('.game-layout').replaceChildren(el('p', { class: 'muted' }, `加载失败：${e.message}`), el('a', { href: './' }, '返回游戏广场'));
});
