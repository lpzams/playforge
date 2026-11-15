import { afterEach, describe, expect, it, vi } from 'vitest';
import { flushPromises, mount } from '@vue/test-utils';
import type { ProjectView } from '../api/types';
import HomePage from './HomePage.vue';
import { globalPlugins, json, stubApi, testRouter } from '../test-utils';

function project(id: string, title: string, createdAt: string, status: string, previewAvailable = false): ProjectView {
  return {
    id, title, prompt: title, createdAt, previewAvailable,
    runs: [{ id: 1, projectId: id, instruction: title, status: status as never, error: null, baseCommit: null,
      resultCommit: null, createdAt, startedAt: null, finishedAt: null, steps: [] }],
  };
}

async function mountPage() {
  const router = testRouter();
  const push = vi.spyOn(router, 'push');
  const w = mount(HomePage, { global: globalPlugins(router) });
  await flushPromises();
  return { w, push };
}

afterEach(() => vi.unstubAllGlobals());

describe('HomePage', () => {
  it('lists projects newest first with status', async () => {
    stubApi([['GET', /^\/api\/projects$/, () => json([
      project('aaaaaaaaaaaa', '旧游戏', '2026-11-10T00:00:00Z', 'SUCCEEDED', true),
      project('bbbbbbbbbbbb', '新游戏', '2026-11-14T00:00:00Z', 'RUNNING'),
    ])]]);
    const { w } = await mountPage();

    const cards = w.findAll('[data-test="project-card"]');
    expect(cards).toHaveLength(2);
    expect(cards[0].text()).toContain('新游戏');
    expect(cards[0].text()).toContain('生成中');
    expect(cards[1].text()).toContain('旧游戏');
    expect(cards[1].text()).toContain('已完成');
    expect(cards[1].text()).toContain('可试玩');
  });

  it('search filters the game list by title or prompt', async () => {
    stubApi([['GET', /^\/api\/projects$/, () => json([
      project('aaaaaaaaaaaa', '打砖块', '2026-11-10T00:00:00Z', 'SUCCEEDED', true),
      project('bbbbbbbbbbbb', '贪吃蛇', '2026-11-14T00:00:00Z', 'SUCCEEDED'),
    ])]]);
    const { w } = await mountPage();

    await w.find('input[data-test="search"]').setValue('砖');
    expect(w.findAll('[data-test="project-card"]')).toHaveLength(1);
    expect(w.text()).toContain('打砖块');
  });

  it('card shows the cover when a preview exists', async () => {
    stubApi([['GET', /^\/api\/projects$/, () => json([project('aaaaaaaaaaaa', '打砖块', '2026-11-10T00:00:00Z', 'SUCCEEDED', true)])]]);
    const { w } = await mountPage();
    expect(w.find('[data-test="project-card"] img').attributes('src')).toBe('/preview/aaaaaaaaaaaa/cover.png');
  });

  it('submit creates the project and navigates to it', async () => {
    const fetchMock = stubApi([
      ['GET', /^\/api\/projects$/, () => json([])],
      ['POST', /^\/api\/projects$/, () => json({ projectId: 'cccccccccccc', runId: 9 }, 201)],
    ]);
    const { w, push } = await mountPage();

    await w.find('textarea').setValue('躲陨石的太空飞船游戏');
    await w.find('[data-test="submit"]').trigger('click');
    await flushPromises();

    const post = fetchMock.mock.calls.find(([, init]) => init?.method === 'POST')!;
    expect(JSON.parse(post[1]!.body as string)).toEqual({ prompt: '躲陨石的太空飞船游戏' });
    expect(push).toHaveBeenCalledWith('/projects/cccccccccccc');
  });

  it('double click creates only once', async () => {
    const fetchMock = stubApi([
      ['GET', /^\/api\/projects$/, () => json([])],
      ['POST', /^\/api\/projects$/, () => new Promise<Response>(() => {})],
    ]);
    const { w } = await mountPage();

    await w.find('textarea').setValue('贪吃蛇');
    await w.find('[data-test="submit"]').trigger('click');
    await w.find('[data-test="submit"]').trigger('click');
    await flushPromises();

    expect(fetchMock.mock.calls.filter(([, init]) => init?.method === 'POST')).toHaveLength(1);
  });

  it('empty prompt disables submit', async () => {
    stubApi([['GET', /^\/api\/projects$/, () => json([])]]);
    const { w } = await mountPage();
    await w.find('textarea').setValue('   ');
    expect(w.find('[data-test="submit"]').attributes('disabled')).toBeDefined();
  });

  it('example fills the prompt', async () => {
    stubApi([['GET', /^\/api\/projects$/, () => json([])]]);
    const { w } = await mountPage();
    await w.find('[data-test="example"]').trigger('click');
    expect((w.find('textarea').element as HTMLTextAreaElement).value.length).toBeGreaterThan(5);
  });

  it('shows the error from the API', async () => {
    stubApi([
      ['GET', /^\/api\/projects$/, () => json([])],
      ['POST', /^\/api\/projects$/, () => json({ error: 'prompt must not be blank' }, 400)],
    ]);
    const { w, push } = await mountPage();

    await w.find('textarea').setValue('x');
    await w.find('[data-test="submit"]').trigger('click');
    await flushPromises();

    expect(w.text()).toContain('prompt must not be blank');
    expect(push).not.toHaveBeenCalled();
  });
});
