<script setup lang="ts">
import type { RunView, StepView } from '../api/types';
import StatusTag from './StatusTag.vue';

defineProps<{ runs: RunView[]; selectedId: number | null }>();
defineEmits<{ select: [id: number] }>();

const STEP_NAMES: Record<string, string> = { CODE: '写代码', REVIEW: '审查', FIX: '修复', PLAYTEST: '试玩', COMMIT: '提交' };

function stepClass(s: StepView) {
  if (s.status === 'RUNNING') return 'running';
  if (s.status === 'FAILED' || s.ok === false) return 'bad';
  return 'ok';
}

const firstLine = (s: string) => s.split('\n')[0];
</script>

<template>
  <ol class="timeline">
    <li
      v-for="(r, i) in runs"
      :key="r.id"
      :class="{ selected: r.id === selectedId }"
      data-test="run"
      @click="$emit('select', r.id)"
    >
      <div class="head">
        <span class="label">{{ i === 0 ? '首次生成' : `修改 ${i}` }}</span>
        <StatusTag :status="r.status" />
      </div>
      <div class="instruction plain-text">{{ firstLine(r.instruction) }}</div>
      <div class="steps">
        <span v-for="s in r.steps" :key="s.id" class="step" :class="stepClass(s)">{{ STEP_NAMES[s.type] ?? s.type }}</span>
      </div>
      <div v-if="r.error && r.status !== 'SUCCEEDED'" class="error plain-text">{{ r.error }}</div>
    </li>
  </ol>
</template>

<style scoped>
.timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 8px; }
li { background: #fff; border: 1px solid var(--pf-border); border-radius: 6px; padding: 10px 12px; cursor: pointer; }
li.selected { border-color: var(--el-color-primary); box-shadow: 0 0 0 1px var(--el-color-primary) inset; }
.head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 4px; }
.label { font-weight: 600; }
.instruction { font-size: 13px; color: var(--pf-text); overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.steps { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 8px; }
.step { font-size: 12px; padding: 1px 6px; border-radius: 3px; border: 1px solid; }
.step.ok { color: var(--el-color-success); border-color: var(--el-color-success-light-5); }
.step.bad { color: var(--el-color-danger); border-color: var(--el-color-danger-light-5); }
.step.running { color: var(--el-color-primary); border-color: var(--el-color-primary-light-5); animation: blink 1.2s infinite; }
.error { margin-top: 6px; font-size: 12px; color: var(--el-color-danger); }
@keyframes blink { 50% { opacity: 0.4; } }
</style>
