// Mirrors the backend DTOs (backend/src/main/java/dev/playforge/api/dto).

export type RunStatus = 'QUEUED' | 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED';
export type StepType = 'CODE' | 'REVIEW' | 'FIX' | 'PLAYTEST' | 'COMMIT';

export interface StepView {
  id: number;
  type: StepType;
  round: number;
  trigger: 'NONE' | 'REVIEW' | 'PLAYTEST';
  status: 'RUNNING' | 'DONE' | 'FAILED';
  ok: boolean | null;
}

export interface RunView {
  id: number;
  projectId: string;
  instruction: string;
  status: RunStatus;
  error: string | null;
  baseCommit: string | null;
  resultCommit: string | null;
  createdAt: string;
  startedAt: string | null;
  finishedAt: string | null;
  steps: StepView[];
}

export interface ProjectView {
  id: string;
  title: string;
  prompt: string;
  createdAt: string;
  previewAvailable: boolean;
  runs: RunView[];
}

export interface Created {
  projectId: string;
  runId: number;
}

/** One entry of a run's event log (backend RunEvent). payload content depends on type. */
export interface RunEvent {
  runId: number;
  seq: number;
  ts: string;
  type: string;
  source: string;
  payload: Record<string, unknown>;
}
