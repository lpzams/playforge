import type { RunEvent } from './types';

/** Most recent events kept on screen; older ones are folded into a single "N earlier events" line. */
export const FEED_LIMIT = 500;

/** Every backend RunEventType; the SSE "event:" name is the type. A test keeps it in step with the backend enum. */
export const EVENT_TYPES = [
  'RUN_STARTED', 'STEP_STARTED', 'AGENT_TEXT', 'TOOL_CALL', 'TOOL_RESULT', 'USAGE', 'FILE_CHANGED',
  'REVIEW_RESULT', 'REVIEW_UNPARSEABLE', 'PLAYTEST_RESULT', 'FUN_SCORE', 'RETRY_SCHEDULED', 'STEP_FINISHED', 'RUN_FINISHED', 'LOG',
  'NETWORK_BLOCKED',
] as const;

export interface RunStream {
  close(): void;
}

/**
 * Subscribes to a run's event stream. Events are delivered once each, in seq order: after a dropped connection the
 * browser reconnects with Last-Event-ID and the server may replay a few, which are skipped here. On RUN_FINISHED
 * the stream closes itself; otherwise EventSource would reconnect to a finished run and wait forever.
 */
export function openRunStream(
  runId: number,
  onEvent: (e: RunEvent) => void,
  onFinished?: () => void,
  factory: (url: string) => EventSource = (url) => new EventSource(url),
): RunStream {
  const source = factory(`/api/runs/${runId}/events`);
  let lastSeq = 0;
  let closed = false;

  const close = () => {
    if (closed) return;
    closed = true;
    source.close();
  };

  const handle = (msg: MessageEvent) => {
    if (closed) return;
    let event: RunEvent;
    try {
      event = JSON.parse(msg.data);
    } catch {
      return;
    }
    if (!event || typeof event.seq !== 'number' || event.seq <= lastSeq) return;
    lastSeq = event.seq;
    onEvent(event);
    if (event.type === 'RUN_FINISHED') {
      close();
      onFinished?.();
    }
  };

  for (const type of EVENT_TYPES) source.addEventListener(type, handle as EventListener);
  return { close };
}
