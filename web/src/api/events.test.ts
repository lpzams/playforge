import { describe, expect, it, vi } from 'vitest';
import { openRunStream } from './events';
import type { RunEvent } from './types';

/** Controllable stand-in for the browser EventSource. */
class FakeEventSource {
  url: string;
  closed = false;
  listeners = new Map<string, ((e: MessageEvent) => void)[]>();
  constructor(url: string) { this.url = url; }
  addEventListener(type: string, fn: (e: MessageEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  close = vi.fn(() => { this.closed = true; });
  emit(type: string, data: string) {
    for (const fn of this.listeners.get(type) ?? []) fn(new MessageEvent(type, { data }));
  }
  push(seq: number, type = 'LOG') {
    const e: RunEvent = { runId: 1, seq, ts: '2026-11-15T00:00:00Z', type, source: 'system', payload: {} };
    this.emit(type, JSON.stringify(e));
  }
}

function open() {
  let fake!: FakeEventSource;
  const got: number[] = [];
  const onFinished = vi.fn();
  const stream = openRunStream(1, (e) => got.push(e.seq), onFinished, (url) => {
    fake = new FakeEventSource(url);
    return fake as unknown as EventSource;
  });
  return { fake, got, onFinished, stream };
}

describe('openRunStream', () => {
  it('subscribes to the run events URL', () => {
    expect(open().fake.url).toBe('/api/runs/1/events');
  });

  it('delivers events in order', () => {
    const { fake, got } = open();
    fake.push(1); fake.push(2); fake.push(3);
    expect(got).toEqual([1, 2, 3]);
  });

  it('deduplicates by seq after a reconnect replays events', () => {
    const { fake, got } = open();
    fake.push(1); fake.push(2); fake.push(3);
    fake.push(2); fake.push(3); fake.push(4);
    expect(got).toEqual([1, 2, 3, 4]);
  });

  it('closes on RUN_FINISHED and ignores later events', () => {
    const { fake, got, onFinished } = open();
    fake.push(1);
    fake.push(2, 'RUN_FINISHED');
    expect(onFinished).toHaveBeenCalledTimes(1);
    expect(fake.close).toHaveBeenCalled();
    fake.push(3);
    expect(got).toEqual([1, 2]);
  });

  it('close() stops delivery', () => {
    const { fake, got, stream } = open();
    stream.close();
    fake.push(1);
    expect(got).toEqual([]);
    expect(fake.close).toHaveBeenCalled();
  });

  it('ignores malformed data', () => {
    const { fake, got } = open();
    expect(() => fake.emit('LOG', 'not json')).not.toThrow();
    fake.emit('LOG', JSON.stringify({ nope: true }));
    expect(got).toEqual([]);
  });

  it('listens to every backend event type', () => {
    const { fake } = open();
    for (const t of ['RUN_STARTED', 'STEP_STARTED', 'AGENT_TEXT', 'TOOL_CALL', 'TOOL_RESULT', 'USAGE', 'FILE_CHANGED',
      'REVIEW_RESULT', 'REVIEW_UNPARSEABLE', 'PLAYTEST_RESULT', 'RETRY_SCHEDULED', 'STEP_FINISHED', 'RUN_FINISHED', 'LOG']) {
      expect(fake.listeners.has(t), t).toBe(true);
    }
  });
});
