import { describe, expect, it } from 'vitest';
import type { RunEvent } from '../api/types';
import { buildStory, checkName, duration, failText, scoreLine } from './story';

let seq = 0;
const t0 = Date.parse('2026-11-16T09:00:00Z');
function ev(type: string, payload: Record<string, unknown>, atSec: number, source = 'system'): RunEvent {
  return { runId: 1, seq: ++seq, ts: new Date(t0 + atSec * 1000).toISOString(), type, source, payload };
}
const step = (type: string, at: number, trigger = 'NONE') => ev('STEP_STARTED', { type, trigger, round: 0 }, at);

describe('story', () => {
  it('tells a finished run step by step with durations', () => {
    const s = buildStory([
      ev('RUN_STARTED', {}, 0),
      step('CODE', 1),
      ev('FILE_CHANGED', { path: 'src/main.js' }, 5, 'claude'),
      ev('FILE_CHANGED', { path: 'src/game.js' }, 6, 'claude'),
      ev('AGENT_TEXT', { text: '写好了贪吃蛇。' }, 20, 'claude'),
      step('REVIEW', 30),
      ev('REVIEW_RESULT', { issues: [{ severity: 'BLOCKER', file: 'src/main.js', line: 12, message: '空格不能重开' }] }, 50),
      step('FIX', 50, 'REVIEW'),
      step('REVIEW', 60),
      ev('REVIEW_RESULT', { issues: [] }, 70),
      step('PLAYTEST', 70),
      ev('PLAYTEST_RESULT', { passed: true, checks: [{ name: 'load', passed: true }, { name: 'action:left', passed: true }] }, 90),
      step('COMMIT', 90),
      ev('RUN_FINISHED', { status: 'SUCCEEDED', commit: 'abc1234def' }, 91),
    ], { previewBase: '/preview/p/' });

    expect(s.entries.map((e) => e.actor)).toEqual(['Claude', 'Codex', 'Claude', 'Codex', '自动试玩', 'Git']);
    expect(s.entries[0].lines[0]).toBe('根据需求写出了第一版，改动 2 个文件。');
    expect(s.entries[0].time).toBe('29 秒');
    expect(s.entries[1].lines[0]).toBe('指出 1 个必须修改的问题：');
    expect(s.entries[1].quotes).toEqual([{ loc: 'src/main.js:12', text: '空格不能重开' }]);
    expect(s.entries[2].lines[0]).toBe('按审查意见修改了代码。');
    expect(s.entries[3].lines[0]).toBe('复查了修改，没有再发现问题。');
    expect(s.entries[4].lines[0]).toBe('在浏览器里试玩了一遍，2 项硬性检查全部通过。');
    expect(s.entries[5].lines[0]).toBe('提交 abc1234，游戏上线。');
    expect(s.entries.every((e) => !e.running)).toBe(true);
    expect(s.note).toBe('写好了贪吃蛇。');
    expect(s.finished).toBe('SUCCEEDED');
  });

  it('marks the step in progress', () => {
    const s = buildStory([ev('RUN_STARTED', {}, 0), step('CODE', 1)], { previewBase: '/preview/p/' });
    expect(s.entries).toHaveLength(1);
    expect(s.entries[0].running).toBe(true);
    expect(s.entries[0].lines[0]).toBe('正在写代码…');
    expect(s.finished).toBeNull();
  });

  it('explains failed hard checks, the AI round and the judge', () => {
    const s = buildStory([
      step('PLAYTEST', 0),
      ev('PLAYTEST_RESULT', {
        passed: false,
        checks: [{ name: 'action:left', passed: false, detail: 'pressing ArrowLeft for 400ms changed no entities' }],
      }, 5),
      step('FIX', 5, 'PLAYTEST'),
      step('PLAYTEST', 20),
      ev('PLAYTEST_RESULT', {
        passed: true, checks: [{ name: 'load', passed: true }],
        aiPlay: {
          ran: true, inconclusive: false, waived: false,
          assertions: [{ message: '按 ← 键后篮子向左移动', passed: true }, { message: '漏掉三个金币后游戏结束', passed: true }],
          shots: [{ file: 'playtest/01-start.png', label: 'start' }],
        },
      }, 30),
      ev('FUN_SCORE', { scores: { fit: 5, difficulty: 3, visuals: 3, feedback: 4 }, summary: '能玩', suggestions: ['加音效'] }, 40),
    ], { previewBase: '/preview/p/' });

    const first = s.entries[0];
    expect(first.bad).toBe(true);
    expect(first.failures).toEqual([{ text: '按←键后，游戏没有任何反应', detail: '按键 left：pressing ArrowLeft for 400ms changed no entities' }]);
    expect(s.entries[1].lines[0]).toBe('按试玩报告修复了问题。');
    const ai = s.entries[2].ai!;
    expect(ai.lead).toBe('AI 试玩员又亲自玩了一局，2 项检查全部通过：');
    expect(ai.items.map((i) => i.text)).toEqual(['按 ← 键后篮子向左移动', '漏掉三个金币后游戏结束']);
    expect(ai.shots).toEqual([{ src: '/preview/p/playtest/01-start.png', label: 'start' }]);
    const judge = s.entries[3];
    expect(judge.actor).toBe('AI 评委');
    expect(judge.lines).toEqual(['契合度 5 · 难度 3 · 画面 3 · 反馈感 4', '能玩']);
    expect(judge.hints).toEqual(['加音效']);
  });

  it('only the latest build has screenshots on disk', () => {
    const events = [step('PLAYTEST', 0), ev('PLAYTEST_RESULT', {
      passed: true, checks: [], aiPlay: { ran: true, assertions: [{ message: 'x', passed: true }], shots: [{ file: 'playtest/01-start.png', label: 'start' }] },
    }, 5)];
    expect(buildStory(events, { previewBase: null }).entries[0].ai!.shots).toEqual([]);
  });

  it('says why the AI round did not count', () => {
    const inconclusive = buildStory([step('PLAYTEST', 0), ev('PLAYTEST_RESULT', {
      passed: true, checks: [], aiPlay: { ran: false, inconclusive: true, error: 'boom', assertions: [], shots: [] },
    }, 5)], { previewBase: null });
    expect(inconclusive.entries[0].ai!.lead).toBe('AI 试玩员写的脚本没能跑完，这一项不影响结果。');

    const waived = buildStory([step('PLAYTEST', 0), ev('PLAYTEST_RESULT', {
      passed: true, checks: [], aiPlay: { ran: true, inconclusive: false, waived: true, assertions: [{ message: '显示最高分', passed: false }], shots: [] },
    }, 5)], { previewBase: null });
    expect(waived.entries[0].ai!.lead).toBe('AI 试玩员又亲自玩了一局，1 项检查里有 1 项没过（修复轮数已用完，按硬性检查放行）');
    expect(waived.entries[0].bad).toBeFalsy();

    const skipped = buildStory([step('PLAYTEST', 0), ev('PLAYTEST_RESULT', { passed: false, checks: [], aiPlay: { skipped: 'hard checks failed' } }, 5)], { previewBase: null });
    expect(skipped.entries[0].ai).toBeNull();
  });

  it('reports a failed or cancelled run', () => {
    const failed = buildStory([step('CODE', 0), ev('STEP_FINISHED', { status: 'FAILED', error: 'claude timed out' }, 10),
      ev('RUN_FINISHED', { status: 'FAILED', reason: 'CODE step failed: claude timed out' }, 10)], { previewBase: null });
    expect(failed.entries[0].bad).toBe(true);
    expect(failed.entries[0].lines).toContain('这一步失败了：claude timed out');
    expect(failed.finished).toBe('FAILED');
    expect(failed.reason).toBe('CODE step failed: claude timed out');

    const cancelled = buildStory([step('CODE', 0), ev('RUN_FINISHED', { status: 'CANCELLED' }, 3)], { previewBase: null });
    expect(cancelled.entries[0].running).toBe(false);
    expect(cancelled.finished).toBe('CANCELLED');
  });

  it('keeps the coder note, not the player or judge replies', () => {
    const s = buildStory([
      step('CODE', 0), ev('AGENT_TEXT', { text: '第一版说明' }, 5, 'claude'),
      step('PLAYTEST', 6), ev('AGENT_TEXT', { text: '```js\nexport default async function play(t) {}\n```' }, 7, 'claude'),
      ev('PLAYTEST_RESULT', { passed: true, checks: [] }, 9), ev('AGENT_TEXT', { text: '{"scores":{}}' }, 10, 'claude'),
    ], { previewBase: null });
    expect(s.note).toBe('第一版说明');
  });

  it('helpers', () => {
    expect(duration(400)).toBe('不到 1 秒');
    expect(duration(61_000)).toBe('1 分 1 秒');
    expect(checkName('not_blank')).toBe('画面');
    expect(checkName('action:jump')).toBe('按键 jump');
    expect(failText({ name: 'restart', passed: false })).toBe('重新开始后，游戏没有回到初始状态');
    expect(scoreLine({ fit: 4, visuals: 2 })).toBe('契合度 4 · 画面 2');
  });
});
