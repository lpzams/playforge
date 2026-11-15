<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ProjectView } from '../api/types';
import StatusTag from './StatusTag.vue';

const props = defineProps<{ project: ProjectView }>();

const latest = computed(() => props.project.runs[props.project.runs.length - 1]);
const created = computed(() => new Date(props.project.createdAt).toLocaleString('zh-CN', { hour12: false }));
const name = computed(() => props.project.title.split(/[：:]/)[0].replace(/^一个/, ''));
const steps = computed(() => props.project.runs.reduce((n, r) => n + r.steps.length, 0));
const coverFailed = ref(false);

// Placeholder colour derived from the id, so each game keeps its own colour.
const fallbackStyle = computed(() => {
  let h = 0;
  for (const c of props.project.id) h = (h * 31 + c.charCodeAt(0)) % 360;
  return { background: `linear-gradient(135deg, hsl(${h} 65% 48%), hsl(${(h + 45) % 360} 65% 38%))` };
});
</script>

<template>
  <router-link :to="`/projects/${project.id}`" class="card" data-test="project-card">
    <div class="top">
      <div class="avatar">
        <img
          v-if="project.previewAvailable && !coverFailed"
          :src="`/preview/${project.id}/cover.png`"
          alt=""
          loading="lazy"
          @error="coverFailed = true"
        />
        <div v-else class="letter" :style="fallbackStyle">{{ name.slice(0, 1) }}</div>
      </div>
      <div class="title-box">
        <div class="title">{{ name }}</div>
        <span class="ver">版本 {{ project.runs.length }}</span>
      </div>
    </div>
    <p class="desc">{{ project.prompt }}</p>
    <div class="author">
      <span class="faces"><span class="face claude">C</span><span class="face codex">X</span></span>
      Claude × Codex
    </div>
    <div class="stats">
      <div class="rating">
        <StatusTag v-if="latest" :status="latest.status" />
        <span class="muted">{{ steps }} 个步骤</span>
      </div>
      <div class="usage">
        <span>{{ created }}</span>
        <span v-if="project.previewAvailable" class="pill">可试玩</span>
      </div>
    </div>
  </router-link>
</template>

<style scoped>
.card {
  display: flex; flex-direction: column; color: inherit; text-decoration: none;
  background: #fff; border: 1px solid var(--pf-border); border-radius: 8px; padding: 20px 20px 14px;
  transition: border-color .15s, box-shadow .15s;
}
.card:hover { border-color: #c9ced5; box-shadow: 0 4px 14px rgba(31, 35, 40, .08); }
.top { display: flex; gap: 14px; align-items: flex-start; }
.avatar { flex: none; width: 64px; height: 64px; border-radius: 50%; overflow: hidden; background: #0b0d12; }
.avatar img { width: 100%; height: 100%; object-fit: cover; display: block; }
.letter { width: 100%; height: 100%; display: grid; place-items: center; color: #fff; font-size: 26px; font-weight: 700; }
.title-box { min-width: 0; }
.title { font-size: 18px; font-weight: 700; color: var(--pf-primary); line-height: 1.35; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.ver { display: inline-block; margin-top: 5px; font-size: 13px; color: var(--pf-muted); background: #f1f3f5; padding: 0 8px; border-radius: 4px; }
.desc { margin: 14px 0 12px; font-size: 14px; color: var(--pf-body); min-height: 66px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.author { display: flex; align-items: center; gap: 8px; color: var(--pf-primary); font-size: 14px; margin-bottom: 12px; }
.faces { display: flex; }
.face { width: 20px; height: 20px; border-radius: 50%; display: grid; place-items: center; font-size: 10px; font-weight: 700; color: #fff; border: 2px solid #fff; }
.face + .face { margin-left: -6px; }
.face.claude { background: #d97757; }
.face.codex { background: #111827; }
.stats { margin-top: auto; border-top: 1px solid var(--pf-border); padding-top: 10px; }
.rating { display: flex; align-items: center; gap: 8px; font-size: 13px; }
.usage { display: flex; align-items: center; gap: 12px; font-size: 13px; color: var(--pf-muted); margin-top: 6px; }
.pill { margin-left: auto; font-size: 12px; font-weight: 600; color: var(--pf-primary); background: var(--pf-primary-soft); padding: 0 8px; border-radius: 4px; }
</style>
