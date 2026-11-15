<script setup lang="ts">
import { computed } from 'vue';
import type { RunStatus } from '../api/types';

const props = defineProps<{ status: RunStatus }>();

const LABELS: Record<RunStatus, { text: string; type: 'info' | 'primary' | 'success' | 'danger' | 'warning' }> = {
  QUEUED: { text: '排队中', type: 'info' },
  RUNNING: { text: '生成中', type: 'primary' },
  SUCCEEDED: { text: '已完成', type: 'success' },
  FAILED: { text: '失败', type: 'danger' },
  CANCELLED: { text: '已取消', type: 'warning' },
};

const label = computed(() => LABELS[props.status] ?? { text: props.status, type: 'info' as const });
</script>

<template>
  <el-tag :type="label.type" size="small" effect="light">{{ label.text }}</el-tag>
</template>
