// Minimal starter game: move the block with the arrow keys, survive to score, touching a wall ends the game.
// Replace freely, but keep installProbe() wired to the real game state.
import { installProbe, probeEvent } from './probe-sdk.js';

const canvas = document.getElementById('game');
const ctx = canvas.getContext('2d');
const W = canvas.width;
const H = canvas.height;
const SPEED = 140; // px per second

const keys = new Set();
const state = { status: 'ready', score: 0, x: W / 2, y: H - 40 };

function start() {
  if (state.status === 'ready') {
    state.status = 'playing';
    probeEvent('start');
  }
}

function restart() {
  state.status = 'ready';
  state.score = 0;
  state.x = W / 2;
  probeEvent('restart');
}

window.addEventListener('keydown', (e) => {
  keys.add(e.code);
  if (e.code === 'Space') {
    if (state.status === 'gameover') restart();
    else start();
  }
});
window.addEventListener('keyup', (e) => keys.delete(e.code));

function update(dt) {
  if (state.status !== 'playing') return;
  if (keys.has('ArrowLeft')) state.x -= SPEED * dt;
  if (keys.has('ArrowRight')) state.x += SPEED * dt;
  state.score += dt * 10;
  if (state.x < 10 || state.x > W - 10) {
    state.status = 'gameover';
    probeEvent('gameover', { score: Math.floor(state.score) });
  }
}

function draw() {
  ctx.fillStyle = '#1b2030';
  ctx.fillRect(0, 0, W, H);
  ctx.fillStyle = '#ffcc33';
  ctx.fillRect(state.x - 10, state.y - 10, 20, 20);
  ctx.fillStyle = '#ffffff';
  ctx.font = '16px sans-serif';
  ctx.fillText(`Score: ${Math.floor(state.score)}`, 12, 24);
  if (state.status === 'ready') ctx.fillText('Press Space to start', W / 2 - 80, H / 2);
  if (state.status === 'gameover') ctx.fillText('Game over - Space to restart', W / 2 - 110, H / 2);
}

let last = performance.now();
function frame(now) {
  update(Math.min((now - last) / 1000, 0.05));
  last = now;
  draw();
  requestAnimationFrame(frame);
}
requestAnimationFrame(frame);

installProbe({
  getState: () => ({
    status: state.status,
    score: Math.floor(state.score),
    entities: [{ id: 'player', type: 'player', x: Math.round(state.x), y: Math.round(state.y) }],
  }),
  actions: [
    { name: 'left', keys: ['ArrowLeft'] },
    { name: 'right', keys: ['ArrowRight'] },
  ],
  start,
  restart,
});
