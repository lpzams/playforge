<script setup lang="ts">
import { computed } from 'vue';

const props = defineProps<{ projectId: string; available: boolean; version: number }>();

// Same isolation as the backend's CSP for /preview: the game gets an opaque origin.
const SANDBOX = 'allow-scripts allow-pointer-lock';
const base = computed(() => `/preview/${props.projectId}/`);
const src = computed(() => (props.version > 0 ? `${base.value}?v=${props.version}` : base.value));
</script>

<template>
  <div class="pane">
    <div class="bar">
      <span class="title">游戏预览</span>
      <span class="muted hint">点击游戏画面后用键盘操作</span>
      <a v-if="available" :href="base" target="_blank" rel="noopener" class="open">新窗口打开</a>
    </div>
    <div class="stage">
      <iframe v-if="available" :key="src" :src="src" :sandbox="SANDBOX" title="游戏预览" />
      <div v-else class="empty">试玩通过后这里会出现游戏</div>
    </div>
  </div>
</template>

<style scoped>
.pane { display: flex; flex-direction: column; gap: 8px; }
.bar { display: flex; align-items: baseline; gap: 10px; }
.title { font-weight: 600; }
.hint { font-size: 12px; }
.open { margin-left: auto; font-size: 13px; }
/* The only dark area: games are designed against dark backgrounds. */
.stage { background: #111; border-radius: 6px; aspect-ratio: 4 / 3; overflow: hidden; }
iframe { width: 100%; height: 100%; border: 0; display: block; }
.empty { height: 100%; display: flex; align-items: center; justify-content: center; color: #888; }
</style>
