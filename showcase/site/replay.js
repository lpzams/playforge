// Replay of a game's generation, played back like a live stream: every command Claude ran, the code it wrote, its
// edits and failures, Codex's review and the playtest checks appear in order, on a compressed version of the real
// timeline. Uses el/duration/checkName/segments from common.js. All AI text is set with textContent only.

const CAP_GAP = 2200; // ms: long real pauses (the model thinking) are shortened to this
const MIN_GAP = 160;
const TYPE_TEXT = 45; // chars per second at 1x
const TYPE_CODE = 170;
const LINES_PER_S = 7;

const R_ACTORS = {
  CODE: ['Claude', 'claude', 'C', '正在写代码'],
  FIX: ['Claude', 'claude', 'C', '正在修改'],
  REVIEW: ['Codex', 'codex', 'X', '正在审查代码'],
  PLAYTEST: ['自动试玩', 'play', '试', '正在浏览器里试玩'],
  COMMIT: ['Git', 'git', 'G', '正在提交'],
};

/** Tool inputs are stored as (possibly truncated) JSON strings; recover the fields we show. */
function toolInput(raw) {
  try { return JSON.parse(raw); } catch { /* truncated: fall through */ }
  const pick = (k) => {
    const m = raw.match(new RegExp(`"${k}":"((?:[^"\\\\]|\\\\.)*)`));
    if (!m) return undefined;
    const body = m[1].replace(/\\$/, '');
    try { return JSON.parse(`"${body}"`); } catch { return body.replace(/\\n/g, '\n').replace(/\\"/g, '"'); }
  };
  return { command: pick('command'), file_path: pick('file_path'), old_string: pick('old_string'), new_string: pick('new_string') };
}

const rel = (p) => (p || '').replace(/^\/workspace\//, '');
const plain = (t) => t.replace(/\*\*/g, '').replace(/`/g, '');
const clip = (s, n) => (s.length > n ? `${s.slice(0, n)}…` : s);

/** A Bash command as the steps a reader cares about: files written with heredocs, or the command line itself. */
function describeBash(cmd) {
  const items = [];
  const re = /cat\s*>\s*(\S+)\s*<<\s*'?(\w+)'?\n([\s\S]*?)(?:\n\2(?:\n|$)|$)/g;
  let m;
  while ((m = re.exec(cmd))) items.push({ kind: 'write', file: rel(m[1]), code: m[3].replace(/…$/, ' …') });
  if (items.length) return items;
  const line = cmd.split('\n')[0].replace(/^cd \/workspace(\/\w+)?\s*(;|&&)\s*/, '').trim();
  return [{ kind: 'run', text: /<<\s*'?\w+'?$/.test(line) ? `${line} …` : clip(line, 110) }];
}

function resultNote(summary, isError) {
  const s = summary || '';
  const bad = s.split('\n').find((l) => /command not found|ERR!|Error:|error TS|failed/i.test(l));
  if (isError || bad) return { kind: 'err', text: clip((bad || s.split('\n')[0]).replace(/^\/bin\/bash: line \d+: /, ''), 120) };
  if (/built in|dist\/index\.html/.test(s)) return { kind: 'ok', text: '构建通过' };
  return null;
}

/** Flattens the runs into timed beats on a compressed clock. Each beat has a start time and an animation length. */
function buildBeats(game) {
  const beats = [];
  // Only the build's last playthrough has its screenshots on disk (earlier rounds were overwritten).
  const lastRun = game.runs[game.runs.length - 1];
  const lastPlaytestResult = lastRun ? [...lastRun.events].reverse().find((e) => e.type === 'PLAYTEST_RESULT') : null;
  let t = 0;
  let prevTs = null;
  let prevEnd = 0;
  const push = (kind, data, ts, dur = 0) => {
    if (prevTs != null) {
      const gap = Math.min(CAP_GAP, Math.max(MIN_GAP, new Date(ts) - new Date(prevTs)));
      t = Math.max(t + gap, prevEnd + MIN_GAP);
    }
    prevTs = ts;
    beats.push({ t, dur, kind, data });
    prevEnd = t + dur;
  };
  const typing = (s, cps, cap) => Math.min(cap, (s.length / cps) * 1000);

  for (const run of game.runs) {
    const segs = segments(run);
    const first = run.events[0];
    if (first) push('prompt', { text: run.prompt || game.prompt }, first.ts, typing(run.prompt || game.prompt, TYPE_TEXT * 1.5, 2500));
    for (const seg of segs) {
      push('step', seg, seg.start);
      // In a PLAYTEST step Claude speaks twice: first as the AI player (writes the playthrough), then, after the
      // result, as the judge (reads the screenshots). Neither is the coder's prose.
      const playtest = seg.type === 'PLAYTEST';
      let judging = false;
      let announced = false;
      for (const e of seg.events) {
        const p = e.payload;
        if (playtest && e.source === 'claude' && !judging && !announced) {
          announced = true;
          push('phase', { text: 'AI 试玩员先读代码，再写一段试玩脚本。' }, e.ts);
        }
        if (playtest && judging && e.type === 'TOOL_CALL' && p.name === 'Read') {
          const file = rel(toolInput(p.input || '').file_path).replace(/^dist\//, '');
          if (/\.png$/.test(file)) push('look', { file }, e.ts, 700);
          continue;
        }
        if (playtest && e.type === 'AGENT_TEXT' && e.source === 'claude') {
          const script = !judging && (p.text.match(/```(?:js|javascript)?\s*\n([\s\S]*?)```/) || [])[1];
          if (script) push('script', { code: script.trim() }, e.ts, typing(script, TYPE_CODE, 4000));
          continue;
        }
        if (e.type === 'TOOL_CALL') {
          const input = toolInput(p.input || '');
          if (p.name === 'Bash' && input.command) {
            for (const it of describeBash(input.command)) push(it.kind, it, e.ts, it.code ? typing(it.code, TYPE_CODE, 3500) : 0);
          } else if (p.name === 'Read') push('read', { file: rel(input.file_path) }, e.ts);
          else if (p.name === 'Write') push('write', { file: rel(input.file_path), code: (input.content || '').replace(/…$/, ' …') }, e.ts, 1500);
          else if (p.name === 'Edit') {
            const lines = (s) => (s || '').replace(/…$/, '').split('\n').slice(0, 8);
            const diff = [...lines(input.old_string).map((l) => ['del', l]), ...lines(input.new_string).map((l) => ['add', l])];
            push('edit', { file: rel(input.file_path), diff }, e.ts, (diff.length / LINES_PER_S) * 1000);
          } else push('run', { text: p.name }, e.ts);
        } else if (e.type === 'TOOL_RESULT') {
          const note = resultNote(p.summary, p.isError);
          if (note) push('note', note, e.ts);
        } else if (e.type === 'AGENT_TEXT' && e.source === 'claude') {
          const text = plain(p.text);
          push('say', { text }, e.ts, typing(text, TYPE_TEXT, 6000));
        } else if (e.type === 'USAGE') {
          push('usage', p, e.ts);
        } else if (e.type === 'REVIEW_RESULT') {
          const n = (p.issues || []).reduce((s, i) => s + i.message.length, 0);
          push('review', { issues: p.issues || [] }, e.ts, typing('x'.repeat(n), TYPE_TEXT * 2, 5000));
        } else if (e.type === 'PLAYTEST_RESULT') {
          const ai = p.aiPlay && !p.aiPlay.skipped ? (p.aiPlay.assertions || []).length : 0;
          push('playtest', { ...p, showShots: e === lastPlaytestResult }, e.ts, ((p.checks || []).length / 4) * 1000 + 300 + ai * 350 + (ai ? 800 : 0));
          judging = true;
        } else if (e.type === 'FUN_SCORE') {
          push('fun', p, e.ts, typing(p.summary || '', TYPE_TEXT, 4000));
        } else if (e.type === 'RUN_FINISHED') {
          push('finished', p, e.ts);
        }
      }
      push('done', seg, seg.end);
    }
  }
  return { beats, total: prevEnd + 600 };
}

const fmt = (ms) => { const s = Math.floor(ms / 1000); return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, '0')}`; };

/**
 * Wires the replay into the story section. `restoreStatic` re-renders the normal (static) story when leaving.
 */
function setupReplay(game, restoreStatic) {
  const section = document.querySelector('.story');
  const feed = document.getElementById('feed');
  const startBtn = document.getElementById('replay');
  const bar = document.getElementById('replay-bar');
  const toggle = bar.querySelector('[data-act="toggle"]');
  const range = bar.querySelector('input[type="range"]');
  const clock = bar.querySelector('.replay-time');
  const { beats, total } = buildBeats(game);
  range.max = String(Math.round(total));

  let now = 0; let speed = 1; let playing = false; let applied = 0; let raf = 0; let last = 0; let follow = true;
  let anims = []; // { node, kind: 'chars'|'lines', full, start, dur, owner }
  let cur = null; // the step entry being built: { body, status, waiting, seg, tokens }
  const stepNo = { n: 0 };

  // ---------- rendering ----------
  const add = (node) => { cur.body.insertBefore(node, cur.waiting); return node; };
  const animate = (node, kind, full, beat) => { anims.push({ node, kind, full, start: beat.t, dur: beat.dur, owner: cur }); };
  const act = (label, text, cls = '') => el('div', { class: `act ${cls}` }, el('span', { class: 'act-k' }, label), el('span', { class: 'act-v' }, text));

  const apply = (b) => {
    const d = b.data;
    switch (b.kind) {
      case 'prompt': {
        const text = el('p', { class: 'entry-text say' });
        const li = el('li', { class: 'entry' }, el('div', { class: 'who user' }, '需'),
          el('div', {}, el('div', { class: 'entry-head' }, el('span', { class: 'name' }, '一句话需求')), text));
        feed.append(li);
        anims.push({ node: text, kind: 'chars', full: d.text, start: b.t, dur: b.dur, owner: null });
        break;
      }
      case 'step': {
        const [name, cls, mono, doing] = R_ACTORS[d.type] || [d.type, '', '·', '进行中'];
        const status = el('span', { class: 'live' }, doing);
        const waiting = el('div', { class: 'waiting' }, d.type === 'REVIEW' ? 'Codex 正在读代码' : d.type === 'PLAYTEST' ? 'AI 试玩员正在工作' : d.type === 'COMMIT' ? '' : 'Claude 正在思考');
        const body = el('div', {}, el('div', { class: 'entry-head' }, el('span', { class: 'name' }, name), status), waiting);
        feed.append(el('li', { class: 'entry active' }, el('div', { class: `who ${cls}` }, mono), body));
        cur = { body, status, waiting, seg: d, tokens: 0 };
        stepNo.n++;
        if (d.type === 'FIX') add(el('p', { class: 'entry-text' }, d.trigger === 'PLAYTEST' ? '收到试玩报告，开始修复。' : '收到审查意见，开始修改。'));
        break;
      }
      case 'write': {
        add(act('写入', d.file));
        const pre = add(el('pre', { class: 'code' }));
        animate(pre, 'chars', d.code.split('\n').slice(0, 16).join('\n') + (d.code.split('\n').length > 16 ? '\n…' : ''), b);
        break;
      }
      case 'run': add(act('运行', d.text)); break;
      case 'read': add(act('读取', d.file)); break;
      case 'edit': {
        add(act('修改', d.file));
        const box = add(el('div', { class: 'diff' }, ...d.diff.map(([k, l]) => el('div', { class: k }, `${k === 'del' ? '−' : '+'} ${l}`))));
        animate(box, 'lines', d.diff.length, b);
        break;
      }
      case 'note': add(act(d.kind === 'err' ? '出错' : '结果', d.text, d.kind)); break;
      case 'say': {
        const p = add(el('p', { class: 'entry-text say' }));
        animate(p, 'chars', d.text, b);
        break;
      }
      case 'usage': cur.tokens += Number(d.inputTokens || 0) + Number(d.outputTokens || 0); break;
      case 'review': {
        const blockers = d.issues.filter((i) => i.severity === 'BLOCKER');
        add(el('p', { class: 'entry-text' }, !d.issues.length ? '审查通过，没有发现问题。'
          : blockers.length ? `指出 ${blockers.length} 个必须修改的问题：` : `提了 ${d.issues.length} 条改进建议：`));
        let offset = 0;
        const totalLen = d.issues.reduce((s, i) => s + i.message.length, 0) || 1;
        for (const it of d.issues) {
          const loc = it.file ? (it.line != null ? `${it.file}:${it.line}` : it.file) : null;
          const msg = el('p', {});
          add(el('div', { class: 'quote' }, loc ? el('span', { class: 'loc' }, loc) : null, msg));
          const share = (it.message.length / totalLen) * b.dur;
          anims.push({ node: msg, kind: 'chars', full: it.message, start: b.t + offset, dur: share, owner: cur });
          offset += share;
        }
        break;
      }
      case 'playtest': {
        const checks = d.checks || [];
        const line = add(el('p', { class: 'checklist' }, ...checks.map((c, i) => el('span', { class: c.passed ? '' : 'fail' }, `${i ? ' · ' : ''}${checkName(c.name)}`))));
        animate(line, 'lines', checks.length, { t: b.t, dur: b.dur - 300 });
        const failed = checks.filter((c) => !c.passed);
        const verdict = add(el('p', { class: `entry-text${failed.length ? ' bad' : ''}` }, failed.length ? '试玩没有通过：' : `${checks.length} 项检查全部通过。`));
        anims.push({ node: verdict, kind: 'show', start: b.t + b.dur - 300, dur: 0, owner: cur });
        const hardEnd = b.t + (checks.length / 4) * 1000;
        if (failed.length) {
          const ul = add(el('ul', { class: 'failed' }, ...failed.map((c) => el('li', {}, failText(c), c.detail ? el('span', {}, `（${checkName(c.name)}：${c.detail}）`) : null))));
          anims.push({ node: ul, kind: 'show', start: hardEnd, dur: 0, owner: cur });
        }
        // The AI player's own round: each assertion it recorded, then its screenshots.
        const ai = d.aiPlay;
        if (ai && !ai.skipped) {
          const asserts = ai.assertions || [];
          const missed = asserts.filter((a) => !a.passed);
          const lead = add(el('p', { class: `entry-text${missed.length && !ai.waived ? ' bad' : ''}` }, ai.inconclusive
            ? 'AI 试玩员的脚本没能跑完，这一项不影响结果。'
            : `AI 试玩员按脚本玩了一局${missed.length ? `，${missed.length} 项没过${ai.waived ? '（修复轮数已用完，按硬性检查放行）' : '：'}` : '：'}`));
          anims.push({ node: lead, kind: 'show', start: hardEnd + 200, dur: 0, owner: cur });
          if (asserts.length) {
            const ul = add(el('ul', { class: 'ai-checks' }, ...asserts.map((a) => el('li', { class: a.passed ? '' : 'fail' }, a.message))));
            animate(ul, 'lines', asserts.length, { t: hardEnd + 400, dur: asserts.length * 350 });
          }
          const shots = d.showShots ? (ai.shots || []).filter((s) => s.file) : [];
          if (shots.length) {
            const strip = add(el('div', { class: 'shots' }, ...shots.map((s) => el('figure', {},
              el('img', { src: `games/${game.id}/play/${s.file}`, alt: s.label }), el('figcaption', {}, s.label)))));
            animate(strip, 'lines', shots.length, { t: hardEnd + 400 + asserts.length * 350, dur: 600 });
          }
        }
        break;
      }
      case 'phase': add(el('p', { class: 'entry-text' }, d.text)); break;
      case 'script': {
        add(act('写出', 'AI 试玩脚本'));
        const pre = add(el('pre', { class: 'code' }));
        animate(pre, 'chars', d.code.split('\n').slice(0, 18).join('\n') + (d.code.split('\n').length > 18 ? '\n…' : ''), b);
        break;
      }
      case 'look': {
        if (!cur.looking) {
          add(el('p', { class: 'entry-text' }, 'AI 评委逐张查看截图：'));
          cur.looking = add(el('div', { class: 'shots looking' }));
        }
        const fig = el('figure', {}, el('img', { src: `games/${game.id}/play/${d.file}`, alt: '' }));
        cur.looking.append(fig);
        break;
      }
      case 'fun': {
        add(el('p', { class: 'entry-text' }, `AI 评委打分（仅供参考）：${scoreLine(d.scores)}`));
        const p = add(el('p', { class: 'entry-text say' }));
        animate(p, 'chars', d.summary || '', b);
        break;
      }
      case 'finished':
        if (cur) add(el('p', { class: 'entry-text' }, d.commit ? `提交 ${d.commit.slice(0, 7)}，游戏上线。` : '运行结束。'));
        break;
      case 'done': {
        if (!cur) break;
        cur.waiting.remove();
        cur.status.className = '';
        cur.status.textContent = [duration(d.ms), cur.tokens ? `${(cur.tokens / 10000).toFixed(1)} 万 tokens` : null].filter(Boolean).join(' · ');
        cur.body.parentElement.classList.remove('active');
        cur = null;
        break;
      }
      default:
    }
  };

  const updateAnims = () => {
    for (const a of anims) {
      const k = a.dur > 0 ? Math.max(0, Math.min(1, (now - a.start) / a.dur)) : now >= a.start ? 1 : 0;
      if (a.kind === 'chars') {
        const n = Math.round(a.full.length * k);
        if (a.shown !== n) { a.node.textContent = a.full.slice(0, n); a.shown = n; }
        a.node.classList.toggle('typing', k > 0 && k < 1);
      } else if (a.kind === 'lines') {
        const n = Math.round(a.full * k);
        if (a.shown !== n) { [...a.node.children].forEach((c, i) => { c.hidden = i >= n; }); a.shown = n; }
      } else if (a.kind === 'show') a.node.hidden = k < 1;
    }
    anims = anims.filter((a) => (a.dur > 0 ? now < a.start + a.dur : now < a.start));
    // The "thinking" line shows only while nothing in the current step is animating.
    if (cur) cur.waiting.hidden = !cur.waiting.textContent || anims.some((a) => a.owner === cur);
  };

  const reset = () => { feed.replaceChildren(); anims = []; cur = null; applied = 0; stepNo.n = 0; };
  const advance = () => {
    while (applied < beats.length && beats[applied].t <= now) apply(beats[applied++]);
    updateAnims();
  };
  const seek = (t) => { now = Math.max(0, Math.min(total, t)); reset(); advance(); updateAnims(); paint(); };

  // ---------- controls ----------
  const paint = () => {
    range.value = String(Math.round(now));
    range.style.setProperty('--p', `${(now / total) * 100}%`);
    clock.textContent = `${fmt(now)} / ${fmt(total)}`;
    toggle.textContent = playing ? '暂停' : now >= total ? '重新播放' : '继续';
  };
  const keepInView = () => {
    if (!follow) return;
    const r = feed.getBoundingClientRect();
    const overflow = r.bottom - (innerHeight - 40);
    if (overflow > 0) scrollBy({ top: overflow, behavior: 'auto' });
  };
  const frame = (ts) => {
    const dt = last ? Math.min(100, ts - last) : 0;
    last = ts;
    if (playing) {
      now = Math.min(total, now + dt * speed);
      advance();
      keepInView();
      if (now >= total) playing = false;
      paint();
    }
    raf = playing ? requestAnimationFrame(frame) : 0;
  };
  const play = () => {
    if (now >= total) seek(0);
    playing = true; last = 0; follow = true;
    if (!raf) raf = requestAnimationFrame(frame);
    paint();
  };
  const pause = () => { playing = false; paint(); };

  const enter = () => {
    section.classList.add('replaying');
    bar.hidden = false; startBtn.hidden = true;
    seek(0);
    section.scrollIntoView({ behavior: 'smooth', block: 'start' });
    setTimeout(play, 400);
  };
  const exit = () => {
    pause();
    section.classList.remove('replaying');
    bar.hidden = true; startBtn.hidden = false;
    feed.replaceChildren(...restoreStatic());
  };

  startBtn.addEventListener('click', enter);
  toggle.addEventListener('click', () => (playing ? pause() : play()));
  bar.querySelector('[data-act="exit"]').addEventListener('click', exit);
  range.addEventListener('input', () => { follow = false; seek(Number(range.value)); });
  for (const chip of bar.querySelectorAll('[data-speed]')) {
    chip.addEventListener('click', () => {
      speed = Number(chip.dataset.speed);
      bar.querySelectorAll('[data-speed]').forEach((c) => c.setAttribute('aria-pressed', String(c === chip)));
    });
  }
  // Stop following the newest line when the reader scrolls up; resume once they come back to the bottom.
  addEventListener('wheel', (e) => { if (e.deltaY < 0) follow = false; }, { passive: true });
  addEventListener('touchmove', () => { follow = false; }, { passive: true });
  addEventListener('scroll', () => { if (feed.getBoundingClientRect().bottom <= innerHeight + 8) follow = true; }, { passive: true });
}
