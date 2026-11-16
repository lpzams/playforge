// Capture bridge for "circle to edit". The game runs in a sandboxed frame with an opaque origin, so the platform
// page cannot read its canvas. This script, injected next to the hi-DPI shim, answers a capture request from the
// parent frame with a PNG of the largest canvas and its logical size (the coordinate space of the game code).
// It only ever answers its parent, echoing the request's nonce; it changes nothing in the game.
(() => {
  if (window.parent === window) return;

  const largestCanvas = () => {
    let best = null;
    let area = 0;
    for (const c of document.querySelectorAll('canvas')) {
      const r = c.getBoundingClientRect();
      if (r.width * r.height > area) { area = r.width * r.height; best = c; }
    }
    return best;
  };

  addEventListener('message', (e) => {
    const req = e.data;
    if (e.source !== window.parent || !req || req.type !== 'playforge:capture' || typeof req.nonce !== 'string') return;
    const reply = (m) => window.parent.postMessage({ type: 'playforge:capture-result', nonce: req.nonce, ...m }, '*');
    const canvas = largestCanvas();
    if (!canvas) { reply({ error: 'no-canvas' }); return; }
    // Read inside an animation frame, after the game's own frame callback: WebGL canvases without
    // preserveDrawingBuffer still hold the frame just drawn at that point.
    requestAnimationFrame(() => {
      try {
        reply({ image: canvas.toDataURL('image/png'), width: canvas.width, height: canvas.height });
      } catch {
        reply({ error: 'unreadable' }); // tainted by a cross-origin image
      }
    });
  });
})();
