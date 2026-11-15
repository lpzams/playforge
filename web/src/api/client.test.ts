import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, ApiError, isActive } from './client';

function respond(status: number, body: unknown) {
  return vi.fn().mockResolvedValue(new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  }));
}

afterEach(() => vi.unstubAllGlobals());

describe('api client', () => {
  it('createProject posts JSON', async () => {
    const fetchMock = respond(201, { projectId: 'abcdefghijkl', runId: 3 });
    vi.stubGlobal('fetch', fetchMock);

    const created = await api.createProject('躲陨石');

    expect(created).toEqual({ projectId: 'abcdefghijkl', runId: 3 });
    const [url, init] = fetchMock.mock.calls[0];
    expect(url).toBe('/api/projects');
    expect(init.method).toBe('POST');
    expect(new Headers(init.headers).get('Content-Type')).toBe('application/json');
    expect(JSON.parse(init.body)).toEqual({ prompt: '躲陨石' });
  });

  it('error body becomes ApiError', async () => {
    vi.stubGlobal('fetch', respond(409, { error: 'project x already has an active run' }));

    const err = await api.createRun('x', 'faster').catch((e) => e);

    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(409);
    expect(err.message).toContain('already has an active run');
  });

  it('error without JSON body still becomes ApiError', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('oops', { status: 502 })));
    const err = await api.listProjects().catch((e) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.status).toBe(502);
  });

  it('cancelRun posts', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 202 }));
    vi.stubGlobal('fetch', fetchMock);

    await api.cancelRun(7);

    expect(fetchMock.mock.calls[0][0]).toBe('/api/runs/7/cancel');
    expect(fetchMock.mock.calls[0][1].method).toBe('POST');
  });

  it('isActive', () => {
    expect(isActive('QUEUED')).toBe(true);
    expect(isActive('RUNNING')).toBe(true);
    expect(isActive('SUCCEEDED')).toBe(false);
  });
});
