import type { Created, ProjectView, RunStatus, RunView } from './types';
import type { MarkupPayload } from '../markup/markup';

export class ApiError extends Error {
  constructor(message: string, readonly status: number) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(method: string, url: string, body?: unknown): Promise<T> {
  const init: RequestInit = { method };
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }
  const res = await fetch(url, init);
  if (!res.ok) {
    let message = `请求失败（HTTP ${res.status}）`;
    try {
      const data = await res.json();
      if (data && typeof data.error === 'string') message = data.error;
    } catch {
      // not JSON: keep the generic message
    }
    throw new ApiError(message, res.status);
  }
  if (res.status === 202 || res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const api = {
  listProjects: () => request<ProjectView[]>('GET', '/api/projects'),
  getProject: (id: string) => request<ProjectView>('GET', `/api/projects/${encodeURIComponent(id)}`),
  createProject: (prompt: string) => request<Created>('POST', '/api/projects', { prompt }),
  createRun: (projectId: string, instruction: string, markup?: MarkupPayload) =>
    request<Created>('POST', `/api/projects/${encodeURIComponent(projectId)}/runs`, markup ? { instruction, markup } : { instruction }),
  markupImageUrl: (runId: number) => `/api/runs/${runId}/markup.png`,
  getRun: (runId: number) => request<RunView>('GET', `/api/runs/${runId}`),
  cancelRun: (runId: number) => request<void>('POST', `/api/runs/${runId}/cancel`),
};

export const isActive = (s: RunStatus) => s === 'QUEUED' || s === 'RUNNING';
