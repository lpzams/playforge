<script setup lang="ts">
import { computed, ref } from 'vue';
import type { ProjectView } from '../api/types';
import { isActive } from '../api/client';

const props = defineProps<{ project: ProjectView }>();

const latest = computed(() => props.project.runs[props.project.runs.length - 1]);
const name = computed(() => props.project.title.split(/[：:]/)[0].replace(/^一个/, ''));
const coverFailed = ref(false);
const when = computed(() => new Date(props.project.createdAt).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' }));

/** One plain line: what is happening now, or what there is to play. Colour only for in-progress and failed. */
const status = computed(() => {
  const r = latest.value;
  if (!r) return { text: '还没有生成', cls: '' };
  if (isActive(r.status)) return { text: props.project.runs.length > 1 ? `正在生成第 ${props.project.runs.length} 版…` : '生成中…', cls: 'live' };
  if (r.status === 'FAILED') return { text: props.project.previewAvailable ? '最近一次修改失败' : '生成失败', cls: 'bad' };
  if (r.status === 'CANCELLED' && !props.project.previewAvailable) return { text: '已取消', cls: '' };
  return { text: props.project.previewAvailable ? '可以玩' : '没有可玩的版本', cls: '' };
});
</script>

<template>
  <router-link :to="`/projects/${project.id}`" class="card" data-test="project-card">
    <div class="thumb">
      <img
        v-if="project.previewAvailable && !coverFailed"
        :src="`/preview/${project.id}/cover.png`"
        alt=""
        loading="lazy"
        @error="coverFailed = true"
      />
      <span v-else class="ph">{{ name.slice(0, 1) }}</span>
    </div>
    <div class="body">
      <div class="title">{{ name }}</div>
      <p class="desc">{{ project.prompt }}</p>
      <div class="meta">
        <span :class="status.cls">{{ status.text }}</span>
        <span>{{ project.runs.length }} 个版本 · {{ when }}</span>
      </div>
    </div>
  </router-link>
</template>

<style scoped>
.card { display: flex; flex-direction: column; gap: 14px; padding: 12px 12px 18px; border-radius: var(--radius-lg); background: var(--surface); color: inherit; transition: background .15s; }
.card:hover { background: var(--surface-hi); }
.thumb { aspect-ratio: 16 / 10; border-radius: var(--radius); overflow: hidden; background: #dde3ea; position: relative; }
.thumb img { width: 100%; height: 100%; object-fit: contain; display: block; background: #111; }
.ph { position: absolute; inset: 0; display: grid; place-items: center; font-size: 44px; color: rgba(31, 31, 31, .32); }
.body { padding: 0 6px; }
.title { font-size: 18px; color: var(--text); margin-bottom: 6px; }
.desc { margin: 0 0 10px; font-size: 14px; color: var(--text-2); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.meta { display: flex; justify-content: space-between; gap: 12px; font-size: 13px; color: var(--muted); }
.live { color: var(--accent); }
.bad { color: var(--danger); }
</style>
