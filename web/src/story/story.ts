// Turns a run's event log into a short story: one entry per step, in plain Chinese, the way the showcase tells it.
// Pure functions, so the wording is unit-tested and the page only renders.
import type { RunEvent, RunStatus } from '../api/types';

export interface Quote { loc: string | null; text: string }
export interface Failure { text: string; detail: string }
export interface AiBlock { lead: string; bad: boolean; items: { text: string; passed: boolean }[]; shots: { src: string; label: string }[] }

export interface Entry {
  key: string;
  actor: string;
  /** Avatar style and letter. */
  cls: string;
  mono: string;
  running: boolean;
  /** Duration of a finished step ("29 秒"), empty while running. */
  time: string;
  lines: string[];
  bad?: boolean;
  quotes: Quote[];
  failures: Failure[];
  /** Names of the passed hard checks, joined. */
  checks: string;
  ai: AiBlock | null;
  hints: string[];
  /** Outbound requests the sandbox network refused in this step ('' when none). */
  blocked: string;
}

export interface Story {
  entries: Entry[];
  /** Claude's last note as the coder (CODE/FIX steps). */
  note: string | null;
  finished: RunStatus | null;
  reason: string | null;
}

interface Check { name: string; passed: boolean; detail?: string }

const ACTORS: Record<string, [string, string, string, string]> = {
  CODE: ['Claude', 'claude', 'C', '正在写代码…'],
  FIX: ['Claude', 'claude', 'C', '正在修改…'],
  REVIEW: ['Codex', 'codex', 'X', '正在审查代码…'],
  PLAYTEST: ['自动试玩', 'play', '试', '正在浏览器里试玩…'],
  COMMIT: ['Git', 'git', 'G', '正在提交…'],
};

const CHECK_NAMES: Record<string, string> = {
  build: '构建', load: '加载', probe: '探针', not_blank: '画面', no_errors: '无报错', restart: '重新开始', harness: '试玩脚本',
};
const KEY_NAMES: Record<string, string> = { Space: '空格', ArrowLeft: '←', ArrowRight: '→', ArrowUp: '↑', ArrowDown: '↓', Enter: '回车' };
const FAIL_TEXT: Record<string, string> = {
  restart: '重新开始后，游戏没有回到初始状态', not_blank: '游戏画面是一片空白', load: '游戏页面打不开',
  no_errors: '运行时有报错', probe: '游戏没有接入测试探针', build: '代码构建失败',
};
const SCORE_NAMES: Record<string, string> = { fit: '契合度', difficulty: '难度', visuals: '画面', feedback: '反馈感' };

export const duration = (ms: number) =>
  ms < 1000 ? '不到 1 秒' : ms < 60_000 ? `${Math.round(ms / 1000)} 秒` : `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`;

export const checkName = (n: string) => (n.startsWith('action:') ? `按键 ${n.slice(7)}` : CHECK_NAMES[n] ?? n);

/** A failed hard check in plain Chinese. */
export function failText(c: Check) {
  if (c.name.startsWith('action:')) {
    const key = (c.detail ?? '').match(/pressing (\w+)/)?.[1];
    return key ? `按${KEY_NAMES[key] ?? key}键后，游戏没有任何反应` : `「${c.name.slice(7)}」操作没有效果`;
  }
  return FAIL_TEXT[c.name] ?? `${checkName(c.name)}没有通过`;
}

export const scoreLine = (scores: Record<string, number> | undefined) =>
  Object.keys(SCORE_NAMES).filter((k) => scores && k in scores).map((k) => `${SCORE_NAMES[k]} ${scores![k]}`).join(' · ');

interface Segment { type: string; trigger: string; start: number; resume: boolean; events: RunEvent[] }

function segmentsOf(events: RunEvent[]): Segment[] {
  const out: Segment[] = [];
  for (const e of events) {
    if (e.type === 'STEP_STARTED') {
      out.push({ type: String(e.payload.type), trigger: String(e.payload.trigger ?? 'NONE'), start: Date.parse(e.ts), resume: e.payload.resume === true, events: [] });
    } else if (out.length) {
      out[out.length - 1].events.push(e);
    }
  }
  return out;
}

const arr = <T>(v: unknown) => (Array.isArray(v) ? (v as T[]) : []);

/**
 * @param opts.followUp   a version after the first: Claude changes existing code instead of writing a first version
 * @param previewBase where the latest build is served ("/preview/<id>/"), for the AI player's screenshots;
 *                    null for older versions, whose screenshots were replaced by the latest build's
 */
export function buildStory(events: RunEvent[], opts: { previewBase: string | null; followUp?: boolean }): Story {
  const segs = segmentsOf(events);
  const finishedEvent = events.find((e) => e.type === 'RUN_FINISHED');
  const finished = (finishedEvent?.payload.status as RunStatus | undefined) ?? null;
  const entries: Entry[] = [];
  let reviews = 0;
  let note: string | null = null;
  // The screenshots on disk are those of the build's last playthrough; earlier rounds were overwritten.
  const lastPlaytest = segs.map((x) => x.type).lastIndexOf('PLAYTEST');

  segs.forEach((seg, i) => {
    const [actor, cls, mono, doing] = ACTORS[seg.type] ?? [seg.type, '', '·', '进行中…'];
    const failedStep = seg.events.find((e) => e.type === 'STEP_FINISHED' && e.payload.status === 'FAILED');
    const stepDone = seg.events.some((e) => e.type === 'STEP_FINISHED');
    const running = i === segs.length - 1 && !finished && !stepDone;
    const cutShort = (i === segs.length - 1 && finished === 'CANCELLED' && !stepDone)
      || failedStep?.payload.error === 'cancelled';
    // A playtest reports before its step ends (the judge is still looking): show results as soon as they exist.
    const hasResult = seg.events.some((e) => e.type === 'REVIEW_RESULT' || e.type === 'PLAYTEST_RESULT');
    // The step was cut off by a restart and resumed: the next segment is the same step starting again.
    const interrupted = i + 1 < segs.length && segs[i + 1].resume && !stepDone;
    const end = i + 1 < segs.length ? segs[i + 1].start
      : Date.parse((finishedEvent ?? seg.events[seg.events.length - 1] ?? { ts: new Date(seg.start).toISOString() }).ts);
    const entry: Entry = {
      key: `${i}`, actor, cls, mono, running, time: running ? '' : duration(Math.max(0, end - seg.start)),
      lines: [], quotes: [], failures: [], checks: '', ai: null, hints: [], blocked: blockedLine(seg.events),
    };
    const say = (s: string) => entry.lines.push(s);

    if (seg.type === 'REVIEW') reviews++;
    if (seg.type === 'CODE' || seg.type === 'FIX') {
      for (const e of seg.events) if (e.type === 'AGENT_TEXT' && e.source === 'claude') note = String(e.payload.text ?? '');
    }

    if (cutShort) {
      say('被取消，没有完成。');
    } else if (failedStep) {
      entry.bad = true;
      say(`这一步失败了：${String(failedStep.payload.error ?? '')}`);
    } else if (interrupted) {
      say('中断后从这里重新开始。');
    } else if (running && !hasResult) {
      say(doing);
    } else if (seg.type === 'CODE') {
      const files = new Set(seg.events.filter((e) => e.type === 'FILE_CHANGED').map((e) => e.payload.path)).size;
      if (opts.followUp) say(files ? `按修改需求改好了代码，改动 ${files} 个文件。` : '按修改需求改好了代码。');
      else say(files ? `根据需求写出了第一版，改动 ${files} 个文件。` : '根据需求写出了第一版。');
    } else if (seg.type === 'FIX') {
      say(seg.trigger === 'PLAYTEST' ? '按试玩报告修复了问题。' : '按审查意见修改了代码。');
    } else if (seg.type === 'REVIEW') {
      const result = seg.events.find((e) => e.type === 'REVIEW_RESULT');
      const issues = arr<{ severity: string; file: string | null; line: number | null; message: string }>(result?.payload.issues);
      const blockers = issues.filter((x) => x.severity === 'BLOCKER');
      if (!issues.length) say(reviews > 1 ? '复查了修改，没有再发现问题。' : '审查通过，没有发现问题。');
      else say(blockers.length ? `指出 ${blockers.length} 个必须修改的问题：` : `提了 ${issues.length} 条改进建议：`);
      entry.quotes = issues.map((x) => ({
        loc: x.file ? (x.line != null ? `${x.file}:${x.line}` : x.file) : null, text: x.message,
      }));
    } else if (seg.type === 'PLAYTEST') {
      const result = seg.events.find((e) => e.type === 'PLAYTEST_RESULT')?.payload;
      const checks = arr<Check>(result?.checks);
      const failed = checks.filter((c) => !c.passed);
      if (failed.length) {
        entry.bad = true;
        say('试玩没有通过：');
        entry.failures = failed.map((c) => ({ text: failText(c), detail: c.detail ? `${checkName(c.name)}：${c.detail}` : '' }));
      } else {
        say(`在浏览器里试玩了一遍，${checks.length} 项硬性检查全部通过。`);
        entry.checks = checks.map((c) => checkName(c.name)).join(' · ');
      }
      entry.ai = aiBlock(result?.aiPlay as Record<string, unknown> | undefined, i === lastPlaytest ? opts.previewBase : null);
      if (entry.ai?.bad) entry.bad = true;
    } else if (seg.type === 'COMMIT') {
      const commit = finishedEvent?.payload.commit as string | undefined;
      say(commit ? `提交 ${commit.slice(0, 7)}，游戏上线。` : '提交代码，游戏上线。');
    }
    entries.push(entry);

    const fun = seg.events.find((e) => e.type === 'FUN_SCORE')?.payload;
    if (fun) {
      entries.push({
        key: `${i}-judge`, actor: 'AI 评委', cls: 'judge', mono: '评', running: false, time: '看截图打分，仅供参考',
        lines: [scoreLine(fun.scores as Record<string, number>), String(fun.summary ?? '')].filter(Boolean),
        quotes: [], failures: [], checks: '', ai: null, hints: arr<string>(fun.suggestions).map(String), blocked: '',
      });
    }
  });

  const raw = (finishedEvent?.payload.reason as string | null | undefined) ?? null;
  const reason = finished === 'CANCELLED' ? '你取消了这一版，游戏保持上一版不变。' : raw;
  return { entries, note, finished, reason };
}

/** "拦截了 3 次外联：evil.com、x.io:8080" for the step's NETWORK_BLOCKED events. */
function blockedLine(events: RunEvent[]): string {
  const reqs = events.filter((e) => e.type === 'NETWORK_BLOCKED')
    .flatMap((e) => arr<{ host: string; port: number; count: number }>(e.payload.requests));
  if (!reqs.length) return '';
  const total = reqs.reduce((n, r) => n + (Number(r.count) || 1), 0);
  const targets = [...new Set(reqs.map((r) => (Number(r.port) === 443 ? r.host : `${r.host}:${r.port}`)))];
  return `拦截了 ${total} 次外联：${targets.join('、')}`;
}

function aiBlock(ai: Record<string, unknown> | undefined, previewBase: string | null): AiBlock | null {
  if (!ai || ai.skipped) return null;
  const shots = previewBase
    ? arr<{ file: string; label: string }>(ai.shots).filter((s) => s.file).map((s) => ({ src: previewBase + s.file, label: s.label }))
    : [];
  if (ai.inconclusive) return { lead: 'AI 试玩员写的脚本没能跑完，这一项不影响结果。', bad: false, items: [], shots };
  const items = arr<{ message: string; passed: boolean }>(ai.assertions).map((a) => ({ text: a.message, passed: !!a.passed }));
  const missed = items.filter((a) => !a.passed).length;
  const lead = missed
    ? `AI 试玩员又亲自玩了一局，${items.length} 项检查里有 ${missed} 项没过${ai.waived ? '（修复轮数已用完，按硬性检查放行）' : '：'}`
    : `AI 试玩员又亲自玩了一局，${items.length} 项检查全部通过：`;
  return { lead, bad: missed > 0 && !ai.waived, items, shots };
}
