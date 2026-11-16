import { afterEach, describe, expect, it, vi } from 'vitest';
import { boxFrom, CaptureError, requestCapture } from './markup';

const PNG = 'data:image/png;base64,iVBORw0KGgo=';

/** An iframe stand-in whose window records posted messages. */
function fakeFrame() {
  const posted: unknown[] = [];
  const win = { postMessage: (m: unknown) => posted.push(m) };
  return { frame: { contentWindow: win } as unknown as HTMLIFrameElement, win, posted };
}

function reply(source: unknown, data: unknown) {
  window.dispatchEvent(new MessageEvent('message', { data, source: source as MessageEventSource }));
}

afterEach(() => vi.useRealTimers());

describe('boxFrom', () => {
  it('spans two points in any order and clamps to the canvas', () => {
    expect(boxFrom(0.6, 0.5, 0.2, 0.1)).toEqual({ x: 0.2, y: 0.1, w: expect.closeTo(0.4), h: expect.closeTo(0.4) });
    expect(boxFrom(-0.5, 0.9, 0.3, 1.4)).toEqual({ x: 0, y: 0.9, w: 0.3, h: expect.closeTo(0.1) });
  });

  it('drops stray clicks', () => {
    expect(boxFrom(0.5, 0.5, 0.505, 0.9)).toBeNull();
  });
});

describe('requestCapture', () => {
  it('asks the frame with a nonce and accepts only its matching reply', async () => {
    const { frame, win, posted } = fakeFrame();
    const p = requestCapture(frame);
    const { nonce } = posted[0] as { type: string; nonce: string };
    expect(posted[0]).toMatchObject({ type: 'playforge:capture' });

    reply({}, { type: 'playforge:capture-result', nonce, image: PNG, width: 1, height: 1 }); // another window
    reply(win, { type: 'playforge:capture-result', nonce: 'other', image: PNG, width: 1, height: 1 });
    reply(win, { type: 'playforge:capture-result', nonce, image: PNG, width: 480.2, height: 320 });
    await expect(p).resolves.toEqual({ image: PNG, width: 480, height: 320 });
  });

  it('rejects errors, non-PNG replies and silence', async () => {
    vi.useFakeTimers();
    const a = fakeFrame();
    const pa = requestCapture(a.frame);
    reply(a.win, { type: 'playforge:capture-result', nonce: (a.posted[0] as { nonce: string }).nonce, error: 'unreadable' });
    await expect(pa).rejects.toThrow('unreadable');

    const b = fakeFrame();
    const pb = requestCapture(b.frame);
    reply(b.win, { type: 'playforge:capture-result', nonce: (b.posted[0] as { nonce: string }).nonce, image: 'data:text/html,x', width: 1, height: 1 });
    await expect(pb).rejects.toBeInstanceOf(CaptureError);

    const c = fakeFrame();
    const pc = requestCapture(c.frame, 1000);
    vi.advanceTimersByTime(1001);
    await expect(pc).rejects.toThrow('timeout');
  });
});
