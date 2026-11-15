<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { api, isActive } from '../api/client';
import { openRunStream, type RunStream } from '../api/events';
import type { ProjectView, RunEvent } from '../api/types';
import RunTimeline from '../components/RunTimeline.vue';
import EventFeed from '../components/EventFeed.vue';
import PreviewPane from '../components/PreviewPane.vue';
import FollowUpBox from '../components/FollowUpBox.vue';
import StatusTag from '../components/StatusTag.vue';

const props = defineProps<{ id: string }>();

const project = ref<ProjectView | null>(null);
const error = ref('');
const selectedRunId = ref<number | null>(null);
const events = ref<RunEvent[]>([]);
const previewVersion = ref(0);
const submitting = ref(false);
let stream: RunStream | null = null;

const latest = computed(() => project.value?.runs[project.value.runs.length - 1] ?? null);
const active = computed(() => !!latest.value && isActive(latest.value.status));

async function load() {
  try {
    project.value = await api.getProject(props.id);
    error.value = '';
    const runs = project.value.runs;
    if (selectedRunId.value == null || !runs.some((r) => r.id === selectedRunId.value)) {
      selectedRunId.value = runs.length ? runs[runs.length - 1].id : null;
    }
  } catch (e) {
    error.value = (e as Error).message;
  }
}

/** Shows one run: the stream replays its history, then follows it live until RUN_FINISHED. */
function follow(runId: number | null) {
  stream?.close();
  stream = null;
  events.value = [];
  if (runId == null) return;
  stream = openRunStream(runId, (e) => {
    events.value.push(e);
    if (e.type === 'STEP_FINISHED' || e.type === 'RUN_FINISHED') void load();
  }, () => {
    previewVersion.value++;
  });
}

watch(selectedRunId, (id) => follow(id));

async function followUp(text: string) {
  submitting.value = true;
  try {
    const created = await api.createRun(props.id, text);
    await load();
    selectedRunId.value = created.runId;
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    submitting.value = false;
  }
}

async function cancel() {
  if (!latest.value) return;
  try {
    await api.cancelRun(latest.value.id);
    await load();
  } catch (e) {
    error.value = (e as Error).message;
  }
}

onMounted(load);
onUnmounted(() => stream?.close());
</script>

<template>
  <div v-if="project" class="project">
    <header class="top panel">
      <router-link to="/" class="back">← 我的游戏</router-link>
      <h2 class="plain-text">{{ project.title.split(/[：:]/)[0] }}</h2>
      <StatusTag v-if="latest" :status="latest.status" />
      <span class="muted">共 {{ project.runs.length }} 版</span>
      <a v-if="project.previewAvailable" class="open" :href="`/preview/${project.id}/`" target="_blank" rel="noopener">新窗口试玩 ↗</a>
    </header>
    <el-alert v-if="error" :title="error" type="error" show-icon class="alert" @close="error = ''" />

    <div class="columns">
      <aside class="left panel">
        <div class="col-title">生成记录</div>
        <RunTimeline :runs="project.runs" :selected-id="selectedRunId" @select="selectedRunId = $event" />
      </aside>

      <section class="middle panel">
        <div class="col-title">生成过程</div>
        <div class="feed-scroll">
          <EventFeed :events="events" />
          <div v-if="events.length === 0" class="muted">等待事件……</div>
        </div>
        <FollowUpBox :active="active" :submitting="submitting" @submit="followUp" @cancel="cancel" />
      </section>

      <section class="right panel">
        <PreviewPane :project-id="project.id" :available="project.previewAvailable" :version="previewVersion" />
        <details class="prompt">
          <summary>最初的需求</summary>
          <div class="plain-text">{{ project.prompt }}</div>
        </details>
      </section>
    </div>
  </div>
  <el-alert v-else-if="error" :title="error" type="error" show-icon :closable="false" />
  <el-skeleton v-else :rows="6" animated />
</template>

<style scoped>
.top { display: flex; align-items: center; gap: 14px; padding: 12px 18px; margin-bottom: 16px; }
.top h2 { margin: 0; font-size: 18px; color: var(--pf-primary); }
.back { color: var(--pf-muted); text-decoration: none; font-size: 13px; white-space: nowrap; }
.open { margin-left: auto; color: var(--pf-primary); text-decoration: none; font-size: 13px; }
.alert { margin-bottom: 12px; }
.columns { display: grid; grid-template-columns: 270px minmax(0, 1fr) 480px; gap: 16px; align-items: start; }
.panel.left, .panel.middle, .panel.right { padding: 14px; }
.left { background: var(--pf-bg); }
.col-title { font-weight: 600; color: var(--pf-text); margin-bottom: 10px; }
.middle { display: flex; flex-direction: column; gap: 10px; }
.feed-scroll { max-height: calc(100vh - 260px); overflow-y: auto; padding-right: 4px; }
.prompt { margin-top: 12px; font-size: 13px; }
.prompt summary { cursor: pointer; color: var(--pf-muted); }
@media (max-width: 1200px) {
  .columns { grid-template-columns: 220px minmax(0, 1fr); }
  .right { grid-column: 1 / -1; }
}
</style>
