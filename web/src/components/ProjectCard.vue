<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ProjectView } from '../api/types';
import { isActive } from '../api/client';
import StatusTag from './StatusTag.vue';

const props = defineProps<{ project: ProjectView }>();

const latest = computed(() => props.project.runs[props.project.runs.length - 1]);
const running = computed(() => !!latest.value && isActive(latest.value.status));
const created = computed(() => new Date(props.project.createdAt).toLocaleString('zh-CN', { hour12: false }));
const name = computed(() => props.project.title.split(/[：:]/)[0].replace(/^一个/, ''));
const coverFailed = ref(false);

// Placeholder colour derived from the id, so each game keeps its own colour.
const fallbackStyle = computed(() => {
  let h = 0;
  for (const c of props.project.id) h = (h * 31 + c.charCodeAt(0)) % 360;
  return { background: `linear-gradient(135deg, hsl(${h} 70% 45%), hsl(${(h + 50) % 360} 70% 35%))` };
});
</script>

<template>
  <router-link :to="`/projects/${project.id}`" class="card" data-test="project-card">
    <div class="cover">
      <img
        v-if="project.previewAvailable && !coverFailed"
        :src="`/preview/${project.id}/cover.png`"
        :alt="`${name} 截图`"
        loading="lazy"
        @error="coverFailed = true"
      />
      <div v-else class="fallback" :style="fallbackStyle">{{ name.slice(0, 1) }}</div>
      <span v-if="running" class="badge running">生成中…</span>
      <span v-else-if="project.previewAvailable" class="badge">可试玩</span>
    </div>
    <div class="body">
      <div class="title">
        <b>{{ name }}</b>
        <span class="ver">第 {{ project.runs.length }} 版</span>
      </div>
      <p class="desc">{{ project.prompt }}</p>
      <div class="foot">
        <StatusTag v-if="latest" :status="latest.status" />
        <span class="time">{{ created }}</span>
      </div>
    </div>
  </router-link>
</template>

<style scoped>
.card {
  display: flex; flex-direction: column; overflow: hidden;
  background: #fff; border: 1px solid var(--pf-border); border-radius: var(--pf-radius);
  color: inherit; text-decoration: none;
  transition: transform .15s, box-shadow .15s, border-color .15s;
}
.card:hover { transform: translateY(-2px); box-shadow: var(--pf-shadow-lg); border-color: #d6dbe3; }
.cover { position: relative; aspect-ratio: 16 / 10; background: #0b0d12; overflow: hidden; }
.cover img { width: 100%; height: 100%; object-fit: cover; display: block; }
.fallback { position: absolute; inset: 0; display: grid; place-items: center; font-size: 40px; font-weight: 700; color: rgba(255, 255, 255, .92); }
.badge { position: absolute; right: 10px; bottom: 10px; font-size: 12px; font-weight: 600; padding: 3px 10px; border-radius: 999px; background: rgba(255, 255, 255, .95); color: #15803d; box-shadow: var(--pf-shadow); }
.badge.running { color: var(--pf-primary); }
.body { padding: 14px 16px; display: flex; flex-direction: column; gap: 6px; flex: 1; }
.title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.title b { font-size: 16px; color: var(--pf-primary); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ver { flex: none; font-size: 12px; color: var(--pf-muted); background: #f1f3f6; padding: 0 7px; border-radius: 6px; }
.desc { margin: 0; font-size: 13px; color: var(--pf-body); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.foot { margin-top: auto; padding-top: 8px; border-top: 1px solid var(--pf-border); display: flex; align-items: center; gap: 8px; }
.time { margin-left: auto; font-size: 12px; color: var(--pf-muted); }
</style>
