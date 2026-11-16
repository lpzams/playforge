import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { ProjectView, RunEvent, RunStatus, RunView } from '../api/types';
import ProjectPage from './ProjectPage.vue';
import { globalPlugins, json, stubApi } from '../test-utils';

// The game frame never loads in jsdom: stand in for the capture handshake and the canvas drawing.
const capture = vi.hoisted(() => vi.fn());
vi.mock('../markup/markup', async (orig) => ({
  ...(await orig<typeof import('../markup/markup')>()),
  requestCapture: capture,
  annotate: vi.fn(async () => 'data:image/png;base64,annotated'),
}));

/** Stand-in for the browser EventSource; every instance is recorded. */
class FakeEventSource {
  static instances: FakeEventSource[] = [];
  url: string;
  closed = false;
  listeners = new Map<string, ((e: MessageEvent) => void)[]>();
  constructor(url: string) {
    this.url = url;
    FakeEventSource.instances.push(this);
  }
  addEventListener(type: string, fn: (e: MessageEvent) => void) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), fn]);
  }
  close() { this.closed = true; }
  push(e: Partial<RunEvent> & { seq: number; type: string }) {
    const full: RunEvent = { runId: 0, ts: '2026-11-15T00:00:00Z', source: 'system', payload: {}, ...e };
    for (const fn of this.listeners.get(e.type) ?? []) fn(new MessageEvent(e.type, { data: JSON.stringify(full) }));
  }
}

const ID = 'abcdefghijkl';

function run(id: number, status: RunStatus, instruction = '躲陨石'): RunView {
  return { id, projectId: ID, instruction, status, error: null, baseCommit: null, resultCommit: null,
    createdAt: '2026-11-15T00:00:00Z', startedAt: null, finishedAt: null, steps: [] };
}

function project(runs: RunView[], previewAvailable = false): ProjectView {
  return { id: ID, title: '躲陨石', prompt: '躲陨石', createdAt: '2026-11-15T00:00:00Z', previewAvailable, runs };
}

async function mountPage() {
  const w = mount(ProjectPage, { props: { id: ID }, global: globalPlugins() });
  await flushPromises();
  return w;
}

const lastSource = () => FakeEventSource.instances[FakeEventSource.instances.length - 1];

beforeEach(() => {
  FakeEventSource.instances = [];
  vi.stubGlobal('EventSource', FakeEventSource);
});
afterEach(() => vi.unstubAllGlobals());

describe('ProjectPage', () => {
  it('subscribes to the active run and tells it as a story, raw log on demand', async () => {
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'RUNNING')]))]]);
    const w = await mountPage();

    expect(lastSource().url).toBe('/api/runs/5/events');
    lastSource().push({ seq: 1, type: 'STEP_STARTED', payload: { type: 'CODE', trigger: 'NONE', round: 0 } });
    lastSource().push({ seq: 2, type: 'AGENT_TEXT', source: 'claude', payload: { text: '我先写游戏循环' } });
    await flushPromises();
    expect(w.find('[data-test="story-entry"]').text()).toContain('正在写代码');
    expect(w.text()).not.toContain('我先写游戏循环');

    await w.find('[data-test="toggle-log"]').trigger('click');
    expect(w.text()).toContain('我先写游戏循环');
  });

  it('while an older version is shown, a newer running one is polled and the game reloads when it finishes', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    try {
      let status: RunStatus = 'RUNNING';
      stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'SUCCEEDED'), run(6, status)], true))]]);
      const w = await mountPage();
      await w.findAll('[data-test="run"]')[0].trigger('click');
      await flushPromises();
      const before = w.find('iframe').attributes('src');

      status = 'SUCCEEDED';
      await vi.advanceTimersByTimeAsync(4500);
      await flushPromises();
      expect(w.find('iframe').attributes('src')).not.toBe(before);
      expect(w.find('[data-test="cancel"]').exists()).toBe(false);
    } finally {
      vi.useRealTimers();
    }
  });

  it('switches between versions', async () => {
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'SUCCEEDED'), run(6, 'FAILED', '加个最高分')], true))]]);
    const w = await mountPage();

    const versions = w.findAll('[data-test="run"]');
    expect(versions.map((v) => v.text())).toEqual(['第 1 版', '第 2 版（失败）']);
    expect(lastSource().url).toBe('/api/runs/6/events');
    await versions[0].trigger('click');
    await flushPromises();
    expect(lastSource().url).toBe('/api/runs/5/events');
  });

  it('follow-up is disabled while a run is active, and cancel works', async () => {
    const fetchMock = stubApi([
      ['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'RUNNING')]))],
      ['POST', /\/api\/runs\/5\/cancel$/, () => new Response(null, { status: 202 })],
    ]);
    const w = await mountPage();

    expect(w.find('[data-test="follow-up-submit"]').attributes('disabled')).toBeDefined();
    await w.find('[data-test="cancel"]').trigger('click');
    await flushPromises();
    expect(fetchMock.mock.calls.some(([url, init]) => url === '/api/runs/5/cancel' && init?.method === 'POST')).toBe(true);
  });

  it('follow-up creates a run and subscribes to it', async () => {
    let views = [project([run(5, 'SUCCEEDED')]), project([run(5, 'SUCCEEDED'), run(6, 'QUEUED', '敌人再快一点')])];
    const fetchMock = stubApi([
      ['GET', /\/api\/projects\/abcdefghijkl$/, () => json(views.length > 1 ? views.shift() : views[0])],
      ['POST', /\/api\/projects\/abcdefghijkl\/runs$/, () => json({ projectId: ID, runId: 6 }, 201)],
    ]);
    const w = await mountPage();
    expect(w.find('[data-test="cancel"]').exists()).toBe(false);

    await w.find('[data-test="follow-up"] textarea').setValue('敌人再快一点');
    await w.find('[data-test="follow-up-submit"]').trigger('click');
    await flushPromises();

    const post = fetchMock.mock.calls.find(([url, init]) => String(url).endsWith('/runs') && init?.method === 'POST')!;
    expect(JSON.parse(post[1]!.body as string)).toEqual({ instruction: '敌人再快一点' });
    expect(lastSource().url).toBe('/api/runs/6/events');
  });

  it('circle to edit: boxes on a snapshot go with the follow-up, and the story shows the marked screenshot', async () => {
    capture.mockResolvedValue({ image: 'data:image/png;base64,c25hcA==', width: 480, height: 320 });
    let views = [project([run(5, 'SUCCEEDED')], true),
      project([run(5, 'SUCCEEDED'), { ...run(6, 'QUEUED', '框 1 的分数放大'), markup: { canvas: { width: 480, height: 320 }, boxes: [] } }], true)];
    const fetchMock = stubApi([
      ['GET', /\/api\/projects\/abcdefghijkl$/, () => json(views.length > 1 ? views.shift() : views[0])],
      ['POST', /\/api\/projects\/abcdefghijkl\/runs$/, () => json({ projectId: ID, runId: 6 }, 201)],
    ]);
    const w = await mountPage();

    await w.find('[data-test="markup-start"]').trigger('click');
    await flushPromises();
    expect(w.find('[data-test="markup-hint"]').exists()).toBe(true);
    const surface = w.find('[data-test="markup-surface"]');
    (surface.element as HTMLElement).getBoundingClientRect = () => ({ left: 0, top: 0, width: 480, height: 320 }) as DOMRect;
    await surface.trigger('pointerdown', { button: 0, pointerId: 1, clientX: 48, clientY: 32 });
    await surface.trigger('pointerup', { pointerId: 1, clientX: 240, clientY: 160 });
    expect(w.find('[data-test="markup-count"]').text()).toBe('已圈 1 处');

    await w.find('[data-test="follow-up"] textarea').setValue('框 1 的分数放大');
    await w.find('[data-test="follow-up-submit"]').trigger('click');
    await flushPromises();

    const post = fetchMock.mock.calls.find(([url, init]) => String(url).endsWith('/runs') && init?.method === 'POST')!;
    const body = JSON.parse(post[1]!.body as string);
    expect(body.instruction).toBe('框 1 的分数放大');
    expect(body.markup.image).toBe('data:image/png;base64,annotated');
    expect(body.markup.canvas).toEqual({ width: 480, height: 320 });
    expect(body.markup.boxes[0].x).toBeCloseTo(0.1);
    expect(body.markup.boxes[0].w).toBeCloseTo(0.4);
    expect(w.find('[data-test="markup-layer"]').exists()).toBe(false);
    expect(w.find('[data-test="marked-shot"] img').attributes('src')).toBe('/api/runs/6/markup.png');
  });

  it('circle to edit tells the player when the game cannot be captured', async () => {
    capture.mockRejectedValue(new Error('unreadable'));
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'SUCCEEDED')], true))]]);
    const w = await mountPage();
    await w.find('[data-test="markup-start"]').trigger('click');
    await flushPromises();
    expect(w.text()).toContain('截取不到');
    expect(w.find('[data-test="markup-layer"]').exists()).toBe(false);
  });

  it('shows the sandboxed preview when available', async () => {
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'SUCCEEDED')], true))]]);
    const w = await mountPage();

    const iframe = w.find('iframe');
    expect(iframe.attributes('src')).toMatch(/^\/preview\/abcdefghijkl\/(\?v=\d+)?$/);
    expect(iframe.attributes('sandbox')).toBe('allow-scripts allow-pointer-lock');
  });

  it('no preview yet shows a hint', async () => {
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'RUNNING')]))]]);
    const w = await mountPage();
    expect(w.find('iframe').exists()).toBe(false);
    expect(w.text()).toContain('试玩通过后这里会出现游戏');
  });

  it('reloads the project when the run finishes', async () => {
    let calls = 0;
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => {
      calls++;
      return json(calls === 1 ? project([run(5, 'RUNNING')]) : project([run(5, 'SUCCEEDED')], true));
    }]]);
    const w = await mountPage();

    lastSource().push({ seq: 1, type: 'RUN_FINISHED', payload: { status: 'SUCCEEDED' } });
    await flushPromises();

    expect(calls).toBeGreaterThanOrEqual(2);
    expect(w.find('iframe').exists()).toBe(true);
  });

  it('closes the stream on unmount', async () => {
    stubApi([['GET', /\/api\/projects\/abcdefghijkl$/, () => json(project([run(5, 'RUNNING')]))]]);
    const w = await mountPage();
    const source = lastSource();
    w.unmount();
    expect(source.closed).toBe(true);
  });
});
