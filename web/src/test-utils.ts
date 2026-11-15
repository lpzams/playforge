import { vi } from 'vitest';
import { createMemoryHistory, createRouter } from 'vue-router';
import ElementPlus from 'element-plus';

/** A real in-memory router (routes are stubs) so components can navigate in tests. */
export function testRouter() {
  const stub = { template: '<div />' };
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: stub }, { path: '/projects/:id', component: stub }],
  });
  return router;
}

export function globalPlugins(router = testRouter()) {
  return { plugins: [ElementPlus, router] };
}

type Handler = (url: string, init: RequestInit) => Response | Promise<Response>;

/** Stubs fetch with a list of [method, url-regexp, handler] routes; unmatched requests get 404. */
export function stubApi(routes: [string, RegExp, Handler][]) {
  const fetchMock = vi.fn(async (url: string, init: RequestInit = {}) => {
    const method = init.method ?? 'GET';
    for (const [m, re, handler] of routes) if (m === method && re.test(url)) return handler(url, init);
    return new Response(JSON.stringify({ error: `no route for ${method} ${url}` }), { status: 404 });
  });
  vi.stubGlobal('fetch', fetchMock);
  return fetchMock;
}

export const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
