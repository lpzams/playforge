<script setup lang="ts">
// Renders a run's event log. Everything an AI produced is shown with {{ }} interpolation only (plain text):
// the page shares an origin with the API, so rendering AI output as HTML would let it act as the user.
import { computed } from 'vue';
import type { RunEvent } from '../api/types';
import { FEED_LIMIT } from '../api/events';
import ReviewCard from './ReviewCard.vue';
import PlaytestChecklist from './PlaytestChecklist.vue';
import type { Check, ReviewIssue } from './feed-types';

const props = defineProps<{ events: RunEvent[] }>();

const hidden = computed(() => Math.max(0, props.events.length - FEED_LIMIT));
const visible = computed(() => props.events.slice(hidden.value));

const STEP_NAMES: Record<string, string> = { CODE: '写代码', REVIEW: '审查', FIX: '修复', PLAYTEST: 'AI 试玩', COMMIT: '提交' };
const SOURCES: Record<string, string> = { claude: 'Claude', codex: 'Codex', system: '系统' };
const RESULT: Record<string, { text: string; type: 'success' | 'error' | 'warning' | 'info' }> = {
  SUCCEEDED: { text: '生成完成', type: 'success' },
  FAILED: { text: '生成失败', type: 'error' },
  CANCELLED: { text: '已取消', type: 'warning' },
};

const p = (e: RunEvent, key: string) => e.payload?.[key];
const str = (v: unknown) => (v == null ? '' : String(v));

function stepTitle(e: RunEvent) {
  const type = str(p(e, 'type'));
  const round = Number(p(e, 'round') ?? 0);
  const trigger = str(p(e, 'trigger'));
  let title = STEP_NAMES[type] ?? type;
  if (type === 'FIX') title += trigger === 'PLAYTEST' ? '（试玩发现的问题）' : '（审查意见）';
  return round > 0 ? `第 ${round + 1} 轮 · ${title}` : title;
}

const issues = (e: RunEvent) => (Array.isArray(p(e, 'issues')) ? (p(e, 'issues') as ReviewIssue[]) : []);
const checks = (e: RunEvent) => (Array.isArray(p(e, 'checks')) ? (p(e, 'checks') as Check[]) : []);
const failures = (e: RunEvent) => (Array.isArray(p(e, 'failures')) ? (p(e, 'failures') as unknown[]).map(str) : []);
</script>

<template>
  <div class="feed">
    <div v-if="hidden > 0" class="muted folded">已省略 {{ hidden }} 条更早的事件</div>
    <template v-for="e in visible" :key="e.seq">
      <div v-if="e.type === 'STEP_STARTED'" class="item step" data-test="event">
        <el-divider content-position="left">{{ stepTitle(e) }}</el-divider>
      </div>

      <div v-else-if="e.type === 'AGENT_TEXT'" class="item bubble" data-test="event">
        <div class="who">{{ SOURCES[e.source] ?? e.source }}</div>
        <div class="plain-text">{{ str(p(e, 'text')) }}</div>
      </div>

      <div v-else-if="e.type === 'TOOL_CALL'" class="item line" data-test="event">
        <span class="tool">{{ str(p(e, 'name')) }}</span>
        <span class="muted plain-text clip">{{ str(p(e, 'input')) }}</span>
      </div>

      <div v-else-if="e.type === 'TOOL_RESULT'" class="item line" data-test="event">
        <span v-if="p(e, 'isError')" class="err" data-test="tool-error">出错</span>
        <span class="muted plain-text clip">{{ str(p(e, 'summary')) }}</span>
      </div>

      <div v-else-if="e.type === 'FILE_CHANGED'" class="item line" data-test="event">
        <span class="muted">修改了</span> <code>{{ str(p(e, 'path')) }}</code>
      </div>

      <div v-else-if="e.type === 'REVIEW_RESULT'" class="item block" data-test="event">
        <div class="block-title">Codex 审查结果</div>
        <ReviewCard :issues="issues(e)" :parsed="p(e, 'parsed') !== false" />
      </div>

      <div v-else-if="e.type === 'PLAYTEST_RESULT'" class="item block" data-test="event">
        <div class="block-title">
          AI 试玩：<span :class="p(e, 'passed') ? 'ok' : 'err'">{{ p(e, 'passed') ? '通过' : '未通过' }}</span>
        </div>
        <PlaytestChecklist v-if="checks(e).length" :checks="checks(e)" />
        <div v-else-if="failures(e).length" class="plain-text err">{{ failures(e).join('\n') }}</div>
      </div>

      <div v-else-if="e.type === 'USAGE'" class="item line muted small" data-test="event">
        {{ SOURCES[e.source] ?? e.source }} 用量：输入 {{ str(p(e, 'inputTokens')) }} / 输出 {{ str(p(e, 'outputTokens')) }} tokens
      </div>

      <div v-else-if="e.type === 'RETRY_SCHEDULED'" class="item line muted" data-test="event">
        {{ SOURCES[e.source] ?? e.source }} 暂时出错，{{ Math.round(Number(p(e, 'delayMs') ?? 0) / 1000) }} 秒后重试：
        <span class="plain-text">{{ str(p(e, 'error')) }}</span>
      </div>

      <div v-else-if="e.type === 'REVIEW_UNPARSEABLE'" class="item line muted" data-test="event">
        审查输出无法解析，按"无阻塞问题"继续
      </div>

      <div v-else-if="e.type === 'STEP_FINISHED'" class="item line" data-test="event">
        <span v-if="p(e, 'status') === 'FAILED'" class="err plain-text">步骤失败：{{ str(p(e, 'error')) }}</span>
      </div>

      <div v-else-if="e.type === 'RUN_STARTED'" class="item line muted" data-test="event">
        {{ p(e, 'resumed') ? '恢复中断的生成' : '开始生成' }}
      </div>

      <div v-else-if="e.type === 'RUN_FINISHED'" class="item" data-test="event">
        <el-alert
          data-test="run-finished"
          :type="(RESULT[str(p(e, 'status'))] ?? { type: 'info' }).type"
          :title="(RESULT[str(p(e, 'status'))] ?? { text: str(p(e, 'status')) }).text"
          :closable="false"
          show-icon
        >
          <span v-if="p(e, 'reason')" class="plain-text">{{ str(p(e, 'reason')) }}</span>
        </el-alert>
      </div>

      <div v-else class="item line muted plain-text" data-test="event">{{ str(p(e, 'message') ?? e.type) }}</div>
    </template>
  </div>
</template>

<style scoped>
.feed { display: flex; flex-direction: column; gap: 6px; }
.folded { text-align: center; font-size: 12px; padding: 4px; }
.step :deep(.el-divider) { margin: 14px 0 6px; }
.bubble { background: #fff; border: 1px solid var(--pf-border); border-radius: 6px; padding: 8px 12px; }
.who { font-size: 12px; color: var(--pf-muted); margin-bottom: 2px; }
.line { display: flex; gap: 8px; align-items: baseline; font-size: 13px; padding: 0 2px; }
.tool { font-weight: 600; white-space: nowrap; }
.clip { overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.block { background: #fff; border: 1px solid var(--pf-border); border-radius: 6px; padding: 10px 12px; }
.block-title { font-weight: 600; margin-bottom: 6px; }
.small { font-size: 12px; }
.ok { color: var(--el-color-success); }
.err { color: var(--el-color-danger); }
</style>
