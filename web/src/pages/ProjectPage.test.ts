import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { ProjectView, RunEvent, RunStatus } from '../api/types';
import ProjectPage from './ProjectPage.vue';
import { globalPlugins, json, stubApi } from '../test-utils';

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

function run(id: number, status: RunStatus, instruction = '躲陨石') {
  return { id, projectId: ID, instruction, status, error: null, baseCommit: null, resultCommit: null,
    createdAt: '2026-11-15T00:00:00Z', startedAt: null, finishedAt: null, steps: [] };
}

function project(runs: ReturnType<typeof run>[], previewAvailable = false): ProjectView {
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
