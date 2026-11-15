// Game plaza (index.html). Helpers come from common.js.

function card(game) {
  const a = analyse(game);
  const meta = [`用时 ${duration(a.totalMs)}`, a.blockers.length ? `审查中改掉 ${a.blockers.length} 个问题` : '审查一次通过'];
  return el('a', { class: 'card', href: gameUrl(game) },
    thumb(game),
    el('div', { class: 'card-text' },
      el('div', { class: 'card-title' }, shortTitle(game.title)),
      el('p', { class: 'card-desc' }, game.prompt),
      el('div', { class: 'card-meta' }, meta.join(' · '))));
}

const state = { games: [], query: '', sort: 'newest', bugOnly: false };

function render() {
  const q = state.query.trim().toLowerCase();
  let list = state.games.filter((g) => !q || g.title.toLowerCase().includes(q) || g.prompt.toLowerCase().includes(q));
  if (state.bugOnly) list = list.filter((g) => analyse(g).blockers.length > 0);
  list = state.sort === 'fastest'
    ? [...list].sort((a, b) => analyse(a).totalMs - analyse(b).totalMs)
    : [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  document.getElementById('game-list').replaceChildren(...list.map(card));
  document.getElementById('count').textContent = `${list.length} 个游戏`;
  document.getElementById('empty').hidden = list.length > 0;
  document.getElementById('sort-newest').setAttribute('aria-pressed', String(state.sort === 'newest'));
  document.getElementById('sort-fastest').setAttribute('aria-pressed', String(state.sort === 'fastest'));
  document.getElementById('filter-bug').setAttribute('aria-pressed', String(state.bugOnly));
}

async function main() {
  document.getElementById('search').addEventListener('input', (e) => { state.query = e.target.value; render(); });
  document.getElementById('sort-newest').addEventListener('click', () => { state.sort = 'newest'; render(); });
  document.getElementById('sort-fastest').addEventListener('click', () => { state.sort = 'fastest'; render(); });
  document.getElementById('filter-bug').addEventListener('click', () => { state.bugOnly = !state.bugOnly; render(); });

  const data = await loadData();
  state.games = data.games;
  document.getElementById('exported-at').textContent = new Date(data.exportedAt).toLocaleString('zh-CN', { hour12: false });
  render();
}

main().catch((e) => {
  document.getElementById('game-list').append(el('p', {}, `加载失败：${e.message}`));
});
