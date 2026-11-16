<template>
  <nav class="topbar">
    <div class="topbar-inner">
      <router-link to="/" class="wordmark">PlayForge</router-link>
      <span class="spacer" />
      <a class="text-link" href="https://lpzams.github.io/playforge/" target="_blank" rel="noopener">作品展</a>
      <a class="text-link" href="https://github.com/lpzams/playforge" target="_blank" rel="noopener">GitHub</a>
    </div>
  </nav>
  <main>
    <router-view />
  </main>
</template>
