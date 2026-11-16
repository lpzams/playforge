<script setup lang="ts">
// The game, as large as the page allows. The stage takes the canvas aspect ratio (cover.json, written by the
// playtest) and the iframe fills it; the preview's index.html loads the hi-DPI shim, so the game renders sharply.
import { computed, ref, watch } from 'vue';

const props = defineProps<{ projectId: string; available: boolean; version: number; title: string }>();

// Same isolation as the backend's CSP for /preview: the game gets an opaque origin.
const SANDBOX = 'allow-scripts allow-pointer-lock';
const PAD = 20; // matches the shim's padding around the canvas (10 px on each side)

const stage = ref<HTMLElement | null>(null);
const frame = ref<HTMLIFrameElement | null>(null);
const reloads = ref(0);
const ratio = ref(1.5);

const base = computed(() => `/preview/${props.projectId}/`);
const src = computed(() => {
  const v = props.version + reloads.value;
  return v > 0 ? `${base.value}?v=${v}` : base.value;
});

async function loadRatio() {
  if (!props.available) return;
  try {
    const res = await fetch(`${base.value}cover.json`);
    if (!res.ok) return;
    const s = (await res.json()) as { width?: number; height?: number };
    if (s.width && s.height) ratio.value = (s.width + PAD) / (s.height + PAD);
  } catch {
    // keep the default ratio
  }
}
watch(() => [props.available, props.version, props.projectId], loadRatio, { immediate: true });

function focusGame() {
  try { frame.value?.contentWindow?.focus(); } catch { /* opaque origin: ignore */ }
}

defineExpose({
  reload: () => { reloads.value++; },
  fullscreen: () => {
    if (document.fullscreenElement) void document.exitFullscreen();
    else void stage.value?.requestFullscreen?.();
  },
});
</script>

<template>
  <div ref="stage" class="stage" :style="{ '--ar': ratio }">
    <iframe v-if="available" ref="frame" :key="src" :src="src" :sandbox="SANDBOX" :title="title" allow="fullscreen" @load="focusGame" />
    <div v-else class="empty">
      <p>试玩通过后这里会出现游戏</p>
      <slot />
    </div>
  </div>
</template>

<style scoped>
.stage {
  position: relative; width: min(100%, calc((100vh - 230px) * var(--ar))); aspect-ratio: var(--ar);
  margin: 0 auto;
  background: #111; border-radius: var(--radius-lg); overflow: hidden;
}
.stage:fullscreen { border-radius: 0; aspect-ratio: auto; width: 100vw; height: 100vh; }
iframe { position: absolute; inset: 0; width: 100%; height: 100%; border: 0; display: block; }
.empty { position: absolute; inset: 0; display: grid; place-content: center; text-align: center; color: #9aa0a6; gap: 6px; padding: 24px; }
.empty p { margin: 0; font-size: 15px; }
</style>
