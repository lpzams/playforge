// Sharp rendering for the showcased games. The generated games draw on a small fixed canvas (e.g. 480x320) using
// hard-coded coordinates; stretched with CSS to fill a big stage they look blurry. This shim, loaded before the game,
// keeps the canvas's logical size (what the game sees as canvas.width/height) but gives it a backing store sized to
// the real on-screen pixels and pre-scales every 2D context, so text and shapes are rasterised at full resolution.
// It also sizes the canvas to fill the window, keeping its aspect ratio. Only the showcase copies load it.
(() => {
  const PAD = 10; // px of the page background kept around the canvas
  const proto = HTMLCanvasElement.prototype;
  const desc = (k) => Object.getOwnPropertyDescriptor(proto, k);
  const W = desc('width');
  const H = desc('height');
  const getContext = proto.getContext;
  const setTransform = CanvasRenderingContext2D.prototype.setTransform;
  const state = new WeakMap(); // canvas -> { w, h, s, ctx }

  const apply = (c, st) => {
    // Resizing the backing store clears it. Keep the current frame, so games that only redraw on input
    // (turn-based, paused) don't go blank when the window is resized.
    let snapshot = null;
    if (st.ctx && W.get.call(c) > 0 && H.get.call(c) > 0) {
      snapshot = document.createElement('canvas');
      W.set.call(snapshot, W.get.call(c));
      H.set.call(snapshot, H.get.call(c));
      getContext.call(snapshot, '2d').drawImage(c, 0, 0);
    }
    W.set.call(c, Math.round(st.w * st.s));
    H.set.call(c, Math.round(st.h * st.s));
    if (st.ctx) {
      if (snapshot) st.ctx.drawImage(snapshot, 0, 0, W.get.call(c), H.get.call(c));
      setTransform.call(st.ctx, st.s, 0, 0, st.s, 0, 0);
    }
  };

  const fit = (c) => {
    const st = state.get(c);
    const k = Math.max(0.1, Math.min((innerWidth - PAD * 2) / st.w, (innerHeight - PAD * 2) / st.h));
    c.style.width = `${Math.floor(st.w * k)}px`;
    c.style.height = `${Math.floor(st.h * k)}px`;
    const s = Math.min(4, Math.max(1, Math.ceil(k * (devicePixelRatio || 1))));
    if (s !== st.s) { st.s = s; apply(c, st); }
  };

  const adopt = (c) => {
    if (state.has(c)) return;
    const st = { w: W.get.call(c), h: H.get.call(c), s: 1, ctx: null };
    state.set(c, st);
    Object.defineProperty(c, 'width', { configurable: true, get: () => st.w, set: (v) => { st.w = +v; apply(c, st); fit(c); } });
    Object.defineProperty(c, 'height', { configurable: true, get: () => st.h, set: (v) => { st.h = +v; apply(c, st); fit(c); } });
    c.style.imageRendering = 'auto';
    fit(c);
  };

  proto.getContext = function (type, opts) {
    const ctx = getContext.call(this, type, opts);
    const st = state.get(this);
    if (type === '2d' && ctx && st && !st.ctx) {
      st.ctx = ctx;
      // The game's own transforms are expressed in logical pixels; fold the backing-store scale back in.
      ctx.setTransform = function (a, b, c, d, e, f) {
        if (typeof a === 'object' && a) { const m = new DOMMatrix([a.a ?? a.m11, a.b ?? a.m12, a.c ?? a.m21, a.d ?? a.m22, a.e ?? a.m41, a.f ?? a.m42]); return setTransform.call(this, new DOMMatrix([st.s, 0, 0, st.s, 0, 0]).multiply(m)); }
        if (a === undefined) return setTransform.call(this, st.s, 0, 0, st.s, 0, 0);
        return setTransform.call(this, a * st.s, b * st.s, c * st.s, d * st.s, e * st.s, f * st.s);
      };
      ctx.resetTransform = function () { setTransform.call(this, st.s, 0, 0, st.s, 0, 0); };
      setTransform.call(ctx, st.s, 0, 0, st.s, 0, 0);
    }
    return ctx;
  };

  const adoptAll = () => document.querySelectorAll('canvas').forEach(adopt);
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', adoptAll); else adoptAll();
  addEventListener('resize', () => document.querySelectorAll('canvas').forEach((c) => state.has(c) && fit(c)));
})();
