<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue';
import { api, isActive } from '../api/client';
import { openRunStream, type RunStream } from '../api/events';
import type { ProjectView, RunEvent, RunView } from '../api/types';
import { duration } from '../story/story';
import Composer from '../components/Composer.vue';
import GameStage from '../components/GameStage.vue';
import RunStory from '../components/RunStory.vue';
import MarkupLayer from '../components/MarkupLayer.vue';
import { annotate, MAX_BOXES, type Box, type Snapshot } from '../markup/markup';

const props = defineProps<{ id: string }>();

const project = ref<ProjectView | null>(null);
const error = ref('');
const selectedRunId = ref<number | null>(null);
const events = ref<RunEvent[]>([]);
const previewVersion = ref(0);
const submitting = ref(false);
const stage = ref<InstanceType<typeof GameStage> | null>(null);
const composer = ref<InstanceType<typeof Composer> | null>(null);
let stream: RunStream | null = null;

const runs = computed(() => project.value?.runs ?? []);
const latest = computed(() => runs.value[runs.value.length - 1] ?? null);
const active = computed(() => !!latest.value && isActive(latest.value.status));
const selected = computed(() => runs.value.find((r) => r.id === selectedRunId.value) ?? null);
const name = computed(() => (project.value?.title ?? '').split(/[：:]/)[0].replace(/^一个/, ''));
/** The screenshots on disk belong to the build in /preview: the last successful version. */
const previewRunId = computed(() => [...runs.value].reverse().find((r) => r.status === 'SUCCEEDED')?.id ?? null);

const versionLabel = (r: RunView) => `第 ${runs.value.indexOf(r) + 1} 版`;
const took = (r: RunView) => (r.startedAt && r.finishedAt ? duration(Date.parse(r.finishedAt) - Date.parse(r.startedAt)) : '');

const meta = computed(() => {
  const r = latest.value;
  if (!r) return '';
  const parts = [`共 ${runs.value.length} 版`];
  if (isActive(r.status)) parts.push(`正在生成${versionLabel(r)}`);
  else if (r.status === 'FAILED') parts.push(`${versionLabel(r)}生成失败`);
  else if (r.status === 'CANCELLED') parts.push(`${versionLabel(r)}已取消`);
  else if (took(r)) parts.push(`${versionLabel(r)}用时 ${took(r)}`);
  return parts.join(' · ');
});

let loads = 0;

/** Loads are fired by many events; only the newest response may land (an older one could show a stale status). */
async function load() {
  const mine = ++loads;
  try {
    const p = await api.getProject(props.id);
    if (mine !== loads) return;
    project.value = p;
    error.value = '';
    if (selectedRunId.value == null || !runs.value.some((r) => r.id === selectedRunId.value)) {
      selectedRunId.value = latest.value?.id ?? null;
    }
  } catch (e) {
    if (mine === loads) error.value = (e as Error).message;
  }
}

/** Shows one version: the stream replays its history, then follows it live until RUN_FINISHED. */
function follow(runId: number | null) {
  stream?.close();
  stream = null;
  events.value = [];
  if (runId == null) return;
  stream = openRunStream(runId, (e) => {
    events.value.push(e);
    if (e.type === 'STEP_FINISHED' || e.type === 'RUN_FINISHED') void load();
  }, () => {});
}

watch(selectedRunId, (id) => follow(id));

// The game reloads when the newest version finishes, whichever version is being looked at.
watch(() => latest.value, (now, before) => {
  if (now && before && now.id === before.id && isActive(before.status) && !isActive(now.status)) previewVersion.value++;
});

// Looking at an older version while a new one generates: its stream has ended, so poll the project instead.
const POLL_MS = 4000;
let poll: ReturnType<typeof setInterval> | null = null;
watch(() => active.value && selectedRunId.value !== latest.value?.id, (needed) => {
  if (poll) clearInterval(poll);
  poll = needed ? setInterval(() => void load(), POLL_MS) : null;
});

// Circle to edit: a frozen snapshot of the game over the stage, with the boxes the player drew on it.
const marking = ref<Snapshot | null>(null);
const boxes = ref<Box[]>([]);
const capturing = ref(false);

async function startMarking() {
  if (!stage.value) return;
  capturing.value = true;
  error.value = '';
  try {
    marking.value = await stage.value.capture();
    boxes.value = [];
    composer.value?.focus();
  } catch {
    error.value = '这个游戏的画面截取不到（比如 3D 画布），可以直接用文字描述要改的地方。';
  } finally {
    capturing.value = false;
  }
}

function stopMarking() {
  marking.value = null;
  boxes.value = [];
}

async function followUp(text: string) {
  submitting.value = true;
  try {
    const shot = marking.value;
    const markup = shot && boxes.value.length
      ? { image: await annotate(shot.image, boxes.value), canvas: { width: shot.width, height: shot.height }, boxes: boxes.value }
      : undefined;
    const created = await api.createRun(props.id, text, markup);
    composer.value?.clear();
    stopMarking();
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
onUnmounted(() => {
  stream?.close();
  if (poll) clearInterval(poll);
});
</script>

<template>
  <div v-if="project" class="project">
    <GameStage ref="stage" :project-id="project.id" :available="project.previewAvailable" :version="previewVersion" :title="name">
      <p v-if="active" class="muted">正在生成，完成后会自动出现</p>
      <template v-if="marking" #overlay>
        <MarkupLayer v-model="boxes" :image="marking.image" :width="marking.width" :height="marking.height" />
      </template>
    </GameStage>
    <p v-if="marking" class="mark-hint" data-test="markup-hint">
      在画面上拖动，框出要改的地方（最多 {{ MAX_BOXES }} 处），再在下面说怎么改，可以说“框 1”。
    </p>

    <header class="game-head">
      <div class="title-block">
        <h1>{{ name }}</h1>
        <div class="meta">{{ meta }}</div>
      </div>
      <div v-if="project.previewAvailable" class="actions">
        <button v-if="marking" type="button" class="pill plain" data-test="markup-stop" @click="stopMarking">退出圈选</button>
        <button
          v-else-if="!active"
          type="button"
          class="pill plain"
          data-test="markup-start"
          :disabled="capturing"
          @click="startMarking"
        >圈选修改</button>
        <button type="button" class="pill plain" :disabled="!!marking" @click="stage?.reload()">重新开始</button>
        <button type="button" class="pill plain" @click="stage?.fullscreen()">全屏</button>
        <a class="pill tonal" :href="`/preview/${project.id}/`" target="_blank" rel="noopener">新窗口</a>
      </div>
    </header>

    <p v-if="error" class="error-text">{{ error }}</p>

    <section class="follow">
      <Composer
        ref="composer"
        test-id="follow-up"
        :disabled="active"
        :submitting="submitting"
        :placeholder="active ? '正在生成中，完成后可以继续修改'
          : marking ? '说说框里要怎么改，例如：框 1 的分数放大一点（Ctrl + Enter 发送）'
          : '继续修改，例如：速度再快一点、加一个最高分记录（Ctrl + Enter 发送）'"
        @submit="followUp"
      >
        <template v-if="marking" #left>
          <span class="marked-count" data-test="markup-count">{{ boxes.length ? `已圈 ${boxes.length} 处` : '还没有圈选，发送时只带文字' }}</span>
        </template>
        <template v-if="active" #right>
          <span class="live">正在生成{{ latest ? versionLabel(latest) : '' }}…</span>
          <button type="button" class="pill plain" data-test="cancel" @click="cancel">取消</button>
        </template>
      </Composer>
    </section>

    <section class="story">
      <div class="story-head">
        <h2>它是怎么做出来的</h2>
        <div v-if="runs.length > 1" class="versions">
          <button
            v-for="r in runs"
            :key="r.id"
            type="button"
            class="chip"
            data-test="run"
            :aria-pressed="r.id === selectedRunId"
            @click="selectedRunId = r.id"
          >
            {{ versionLabel(r) }}<span v-if="isActive(r.status)">（生成中）</span><span v-else-if="r.status === 'FAILED'">（失败）</span><span v-else-if="r.status === 'CANCELLED'">（已取消）</span>
          </button>
        </div>
      </div>
      <RunStory
        v-if="selected"
        :events="events"
        :instruction="selected.instruction"
        :label="runs.indexOf(selected) === 0 ? '一句话需求' : `修改需求（${versionLabel(selected)}）`"
        :follow-up="runs.indexOf(selected) > 0"
        :markup-image="selected.markup ? api.markupImageUrl(selected.id) : null"
        :preview-base="selected.id === previewRunId ? `/preview/${project.id}/` : null"
      />
    </section>
  </div>
  <p v-else-if="error" class="error-text page-error">{{ error }}</p>
  <p v-else class="muted page-error">加载中…</p>
</template>

<style scoped>
.project { max-width: 1400px; margin: 0 auto; padding: 4px 24px 64px; }
.game-head { display: flex; align-items: center; gap: 24px; margin: 18px auto 0; max-width: 1100px; }
.title-block { min-width: 0; margin-right: auto; }
.game-head h1 { font-size: 28px; line-height: 1.25; margin: 0 0 4px; letter-spacing: -.3px; }
.meta { font-size: 13.5px; color: var(--muted); }
.actions { display: flex; gap: 8px; flex-shrink: 0; }
.follow, .story, .project > .error-text { max-width: 1100px; margin-left: auto; margin-right: auto; }
.follow { margin-top: 20px; }
.live { color: var(--accent); font-size: 14px; white-space: nowrap; }
.mark-hint { max-width: 1100px; margin: 12px auto 0; font-size: 14px; color: var(--text-2); text-align: center; }
.marked-count { font-size: 13.5px; color: var(--muted); }
.story { margin-top: 40px; }
.story-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; flex-wrap: wrap; margin-bottom: 20px; }
.story-head h2 { font-size: 20px; margin: 0; }
.versions { display: flex; gap: 6px; flex-wrap: wrap; }
.page-error { max-width: 1100px; margin: 48px auto; padding: 0 24px; }
@media (max-width: 760px) {
  .game-head { flex-direction: column; align-items: stretch; gap: 12px; }
  .project { padding: 4px 16px 48px; }
}
</style>
