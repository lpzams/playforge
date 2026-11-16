// "Circle to edit": snapshot the running game, let the player box areas, send the boxes with a marked image.

/** A rectangle normalised to 0..1 of the game canvas. */
export interface Box { x: number; y: number; w: number; h: number }

export interface Snapshot {
  /** PNG data URL of the game canvas. */
  image: string;
  /** Logical canvas size: the coordinate space of the game code. */
  width: number;
  height: number;
}

/** What a follow-up carries to the backend (CreateRunRequest.markup). */
export interface MarkupPayload {
  image: string;
  canvas: { width: number; height: number };
  boxes: Box[];
}

export const MAX_BOXES = 3;
/** Boxes smaller than this (each way, normalised) are taken as stray clicks. */
export const MIN_SIZE = 0.02;

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));

/** The box spanned by two points (normalised, any order), clamped to the canvas; null when too small. */
export function boxFrom(ax: number, ay: number, bx: number, by: number): Box | null {
  const x1 = clamp01(Math.min(ax, bx));
  const y1 = clamp01(Math.min(ay, by));
  const x2 = clamp01(Math.max(ax, bx));
  const y2 = clamp01(Math.max(ay, by));
  if (x2 - x1 < MIN_SIZE || y2 - y1 < MIN_SIZE) return null;
  return { x: x1, y: y1, w: x2 - x1, h: y2 - y1 };
}

export class CaptureError extends Error {}

/**
 * Asks the game frame for a snapshot. The frame has an opaque origin, so the request goes through the bridge the
 * preview page loads (web/public/bridge.js); only a reply from that frame carrying our nonce is accepted.
 */
export function requestCapture(frame: HTMLIFrameElement, timeoutMs = 3000): Promise<Snapshot> {
  const target = frame.contentWindow;
  if (!target) return Promise.reject(new CaptureError('no-frame'));
  const nonce = Math.random().toString(36).slice(2) + Date.now().toString(36);
  return new Promise((resolve, reject) => {
    const done = () => { clearTimeout(timer); window.removeEventListener('message', onMessage); };
    const onMessage = (e: MessageEvent) => {
      const d = e.data as Record<string, unknown> | null;
      if (e.source !== target || !d || d.type !== 'playforge:capture-result' || d.nonce !== nonce) return;
      done();
      const ok = typeof d.image === 'string' && d.image.startsWith('data:image/png;base64,')
        && Number(d.width) > 0 && Number(d.height) > 0;
      if (ok) resolve({ image: d.image as string, width: Math.round(Number(d.width)), height: Math.round(Number(d.height)) });
      else reject(new CaptureError(typeof d.error === 'string' ? d.error : 'bad-reply'));
    };
    const timer = setTimeout(() => { done(); reject(new CaptureError('timeout')); }, timeoutMs);
    window.addEventListener('message', onMessage);
    target.postMessage({ type: 'playforge:capture', nonce }, '*');
  });
}

/** Longest side of the image sent to the coder: enough to read, small enough to upload. */
export const MAX_SIDE = 1280;

function loadImage(src: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new CaptureError('image'));
    img.src = src;
  });
}

/** The snapshot with the numbered boxes drawn on (red, like a reviewer's pen), as a PNG data URL. */
export async function annotate(image: string, boxes: Box[]): Promise<string> {
  const img = await loadImage(image);
  const k = Math.min(1, MAX_SIDE / Math.max(img.naturalWidth, img.naturalHeight));
  const w = Math.max(1, Math.round(img.naturalWidth * k));
  const h = Math.max(1, Math.round(img.naturalHeight * k));
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  if (!ctx) throw new CaptureError('canvas');
  ctx.drawImage(img, 0, 0, w, h);
  const line = Math.max(2, Math.round(Math.min(w, h) / 160));
  const font = Math.max(14, Math.round(Math.min(w, h) / 22));
  boxes.forEach((b, i) => {
    const x = b.x * w;
    const y = b.y * h;
    ctx.strokeStyle = '#ff2d2d';
    ctx.lineWidth = line;
    ctx.strokeRect(x, y, b.w * w, b.h * h);
    // Number tag at the top-left corner, kept inside the image.
    const tag = font + 8;
    const tx = Math.min(Math.max(0, x - line / 2), w - tag);
    const ty = y - tag >= 0 ? y - tag : Math.min(y, h - tag);
    ctx.fillStyle = '#ff2d2d';
    ctx.fillRect(tx, ty, tag, tag);
    ctx.fillStyle = '#fff';
    ctx.font = `600 ${font}px system-ui, sans-serif`;
    ctx.textAlign = 'center';
    ctx.textBaseline = 'middle';
    ctx.fillText(String(i + 1), tx + tag / 2, ty + tag / 2 + 1);
  });
  return canvas.toDataURL('image/png');
}
