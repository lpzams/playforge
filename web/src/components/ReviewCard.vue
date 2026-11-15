<script setup lang="ts">
import type { ReviewIssue } from "./feed-types";

defineProps<{ issues: ReviewIssue[]; parsed: boolean }>();

function where(i: ReviewIssue) {
  if (!i.file) return '';
  return i.line != null ? `${i.file}:${i.line}` : i.file;
}
</script>

<template>
  <div class="review">
    <div v-if="!parsed" class="muted">审查输出无法解析，按"无阻塞问题"继续</div>
    <div v-else-if="issues.length === 0" class="pass">审查通过，没有发现问题</div>
    <ul v-else class="issues">
      <li v-for="(i, k) in issues" :key="k" data-test="review-issue" :class="i.severity === 'BLOCKER' ? 'blocker' : 'suggestion'">
        <el-tag size="small" :type="i.severity === 'BLOCKER' ? 'danger' : 'info'" effect="light">
          {{ i.severity === 'BLOCKER' ? '阻塞' : '建议' }}
        </el-tag>
        <code v-if="where(i)" class="where">{{ where(i) }}</code>
        <span class="plain-text">{{ i.message }}</span>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.issues { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
li { display: flex; gap: 8px; align-items: baseline; }
.where { font-size: 12px; color: var(--pf-muted); white-space: nowrap; }
.pass { color: var(--el-color-success); }
</style>
