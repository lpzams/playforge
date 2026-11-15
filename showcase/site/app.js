// Game plaza (index.html). Helpers come from common.js.

function card(game) {
  const a = analyse(game);
  const passed = a.checks.filter((c) => c.passed).length;
  return el('a', { class: 'card', href: gameUrl(game) },
    el('div', { class: 'card-top' },
      avatar(game),
      el('div', { class: 'title-box' },
        el('div', { class: 'title' }, shortTitle(game.title)),
        el('span', { class: 'ver' }, `版本 ${game.runs.length}`))),
    el('p', { class: 'desc' }, game.prompt),
    el('div', { class: 'author' },
      el('span', { class: 'faces' }, el('span', { class: 'face claude' }, 'C'), el('span', { class: 'face codex' }, 'X')),
      'Claude × Codex'),
    el('div', { class: 'stats' },
      el('div', { class: 'rating' }, el('span', { class: 'star' }, '★ '), `AI 试玩 ${passed}/${a.checks.length} 项通过`),
      el('div', { class: 'usage' },
        el('span', {}, `用时 ${duration(a.totalMs)}`),
        el('span', {}, a.blockers.length ? `修复 ${a.blockers.length} 个 bug` : '审查一次通过'),
        el('span', { class: 'pill' }, '可试玩'))));
}

const state = { games: [], query: '', sort: 'newest', bugOnly: false, view: 'grid' };

function render() {
  const q = state.query.trim().toLowerCase();
  let list = state.games.filter((g) => !q || g.title.toLowerCase().includes(q) || g.prompt.toLowerCase().includes(q));
  if (state.bugOnly) list = list.filter((g) => analyse(g).blockers.length > 0);
  list = state.sort === 'fastest'
    ? [...list].sort((a, b) => analyse(a).totalMs - analyse(b).totalMs)
    : [...list].sort((a, b) => b.createdAt.localeCompare(a.createdAt));

  const grid = document.getElementById('game-list');
  grid.className = state.view === 'list' ? 'cards list' : 'cards';
  grid.replaceChildren(...list.map(card));
  document.getElementById('count').textContent = String(list.length);
  document.getElementById('empty').hidden = list.length > 0;
  document.getElementById('sort').textContent = state.sort === 'fastest' ? '⇅ 用时最短' : '⇅ 最新生成';
  document.getElementById('sort-label').textContent = state.sort === 'fastest' ? '按生成用时排序' : '按生成时间排序';
  document.getElementById('filter-bug').setAttribute('aria-pressed', String(state.bugOnly));
  document.getElementById('view-grid').classList.toggle('active', state.view === 'grid');
  document.getElementById('view-list').classList.toggle('active', state.view === 'list');
}

async function main() {
  document.getElementById('search').addEventListener('input', (e) => { state.query = e.target.value; render(); });
  document.getElementById('sort').addEventListener('click', () => { state.sort = state.sort === 'newest' ? 'fastest' : 'newest'; render(); });
  document.getElementById('filter-bug').addEventListener('click', () => { state.bugOnly = !state.bugOnly; render(); });
  document.getElementById('view-grid').addEventListener('click', () => { state.view = 'grid'; render(); });
  document.getElementById('view-list').addEventListener('click', () => { state.view = 'list'; render(); });

  const data = await loadData();
  state.games = data.games;
  document.getElementById('exported-at').textContent = new Date(data.exportedAt).toLocaleString('zh-CN', { hour12: false });
  render();
}

main().catch((e) => {
  document.getElementById('game-list').append(el('p', {}, `加载失败：${e.message}`));
});
