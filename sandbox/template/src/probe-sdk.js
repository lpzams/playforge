// PlayForge probe protocol v1 (see docs/superpowers/specs/2026-11-08-playforge-design.md §7.1).
// Automated playtests read the game through window.__GAME_PROBE__. Every game must call installProbe().

const events = [];

/**
 * @param {object} options
 * @param {() => {status: 'loading'|'ready'|'playing'|'paused'|'gameover', score: number,
 *                 entities: {id: string, type: string, x: number, y: number}[]}} options.getState
 * @param {{name: string, keys: string[]}[]} options.actions  keys use KeyboardEvent.code names, e.g. 'ArrowLeft', 'Space'
 * @param {() => void} options.start    ready -> playing (same as pressing the start key)
 * @param {() => void} options.restart  back to ready (or playing)
 */
export function installProbe({ getState, actions, start, restart }) {
  window.__GAME_PROBE__ = { version: 1, getState, actions, events, start, restart };
}

/** Records a game event (score, death, level up...) for playtests and replays. */
export function probeEvent(type, data) {
  events.push({ t: performance.now(), type, data });
}
