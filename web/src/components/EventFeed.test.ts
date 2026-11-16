import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import EventFeed from './EventFeed.vue';
import type { RunEvent } from '../api/types';

let seq = 0;
function ev(type: string, payload: Record<string, unknown>, source = 'system'): RunEvent {
  return { runId: 1, seq: ++seq, ts: '2026-11-15T00:00:00Z', type, source, payload };
}

function render(events: RunEvent[]) {
  return mount(EventFeed, { props: { events }, attachTo: document.body });
}

describe('EventFeed', () => {
  it('renders AI text as plain text, never as HTML', () => {
    const evil = '<img src=x onerror="window.__xss=1">你好';
    const w = render([ev('AGENT_TEXT', { text: evil }, 'claude')]);

    expect(w.find('img').exists()).toBe(false);
    expect(w.text()).toContain(evil);
    expect((window as unknown as { __xss?: number }).__xss).toBeUndefined();
    expect(w.text()).toContain('Claude');
    w.unmount();
  });

  it('shows review issues with severity', () => {
    const w = render([ev('REVIEW_RESULT', {
      parsed: true, blockers: 1,
      issues: [
        { severity: 'BLOCKER', file: 'src/main.js', line: 16, message: 'Space after game over does not restart' },
        { severity: 'SUGGESTION', file: 'src/render.js', line: null, message: 'extract constants' },
      ],
    })]);

    const issues = w.findAll('[data-test="review-issue"]');
    expect(issues).toHaveLength(2);
    expect(issues[0].text()).toContain('阻塞');
    expect(issues[0].text()).toContain('src/main.js:16');
    expect(issues[0].text()).toContain('Space after game over does not restart');
    expect(issues[1].text()).toContain('建议');
  });

  it('review without issues says so', () => {
    const w = render([ev('REVIEW_RESULT', { parsed: true, blockers: 0, issues: [] })]);
    expect(w.text()).toContain('审查通过');
  });

  it('caps at the feed limit', () => {
    const events = Array.from({ length: 2000 }, (_, i) => ev('LOG', { message: `line ${i}` }));
    const w = render(events);

    expect(w.findAll('[data-test="event"]')).toHaveLength(500);
    expect(w.text()).toContain('已省略 1500 条更早的事件');
    expect(w.text()).toContain('line 1999');
    expect(w.text()).not.toContain('line 1499');
  });

  it('run finished banner shows the failure reason', () => {
    const w = render([ev('RUN_FINISHED', { status: 'FAILED', reason: 'playtest still failing after 2 fix attempts' })]);
    const banner = w.find('[data-test="run-finished"]');
    expect(banner.text()).toContain('失败');
    expect(banner.text()).toContain('playtest still failing after 2 fix attempts');
  });

  it('step separators name the step in Chinese', () => {
    const w = render([ev('STEP_STARTED', { type: 'REVIEW', round: 1, trigger: 'NONE' })]);
    expect(w.text()).toContain('审查');
  });

  it('file changes and tool calls are compact lines', () => {
    const w = render([
      ev('FILE_CHANGED', { path: 'src/game.js' }, 'claude'),
      ev('TOOL_CALL', { name: 'Bash', input: '{"command":"npm run build"}' }, 'claude'),
      ev('TOOL_RESULT', { summary: 'error TS2304', isError: true }, 'claude'),
    ]);
    expect(w.text()).toContain('src/game.js');
    expect(w.text()).toContain('Bash');
    expect(w.find('[data-test="tool-error"]').exists()).toBe(true);
  });

  it('shows what the AI player checked, and a waived round', () => {
    const w = render([ev('PLAYTEST_RESULT', {
      passed: true, failures: [], checks: [{ name: 'load', passed: true, detail: '' }],
      aiPlay: {
        ran: true, inconclusive: false, waived: true, error: null, log: [],
        assertions: [{ message: 'snake grows after eating', passed: true }, { message: 'score is shown', passed: false }],
        shots: [{ file: 'playtest/01-start.png', label: 'start' }],
      },
    })]);

    const asserts = w.findAll('[data-test="ai-assertion"]');
    expect(asserts).toHaveLength(2);
    expect(asserts[1].text()).toContain('score is shown');
    expect(w.text()).toContain('AI 实玩');
    expect(w.text()).toContain('修复轮数已用完');
    w.unmount();
  });

  it('says why AI play was skipped or inconclusive', () => {
    const w = render([
      ev('PLAYTEST_RESULT', { passed: true, failures: [], checks: [], aiPlay: { skipped: 'player failed: timeout' } }),
      ev('PLAYTEST_RESULT', { passed: true, failures: [], checks: [], aiPlay: { ran: false, inconclusive: true, error: 't.jump is not a function', assertions: [], shots: [], log: [] } }),
    ]);
    expect(w.text()).toContain('player failed: timeout');
    expect(w.text()).toContain('t.jump is not a function');
    w.unmount();
  });

  it('shows the fun score as plain text', () => {
    const w = render([ev('FUN_SCORE', {
      scores: { fit: 4, difficulty: 3, visuals: 2, feedback: 5 }, summary: '<b>能玩</b>', suggestions: ['加音效'], shots: [],
    })]);
    expect(w.find('[data-test="fun-score"]').text()).toContain('契合度 4');
    expect(w.text()).toContain('<b>能玩</b>');
    expect(w.find('b').exists()).toBe(false);
    expect(w.text()).toContain('加音效');
    w.unmount();
  });
});
