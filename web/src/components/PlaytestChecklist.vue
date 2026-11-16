<script setup lang="ts">
import type { Check } from "./feed-types";

defineProps<{ checks: Check[] }>();

const NAMES: Record<string, string> = {
  build: '构建',
  load: '加载',
  probe: '探针',
  not_blank: '画面非空白',
  no_errors: '无报错',
  restart: '重新开始',
  harness: '试玩脚本',
};

function label(name: string) {
  if (name.startsWith('action:')) return `按键 ${name.slice('action:'.length)}`;
  return NAMES[name] ?? name;
}
</script>

<template>
  <ul class="checks">
    <li v-for="c in checks" :key="c.name" data-test="check" :class="c.passed ? 'ok' : 'bad'">
      <span class="mark">{{ c.passed ? '✓' : '✗' }}</span>
      <span class="name">{{ label(c.name) }}</span>
      <span v-if="c.detail" class="detail plain-text">{{ c.detail }}</span>
    </li>
  </ul>
</template>

<style scoped>
.checks { list-style: none; margin: 0; padding: 0; }
li { display: flex; gap: 8px; padding: 2px 0; align-items: baseline; }
.mark { width: 14px; font-weight: 700; }
.ok .mark { color: var(--ok); }
.bad .mark, .bad .name { color: var(--danger); }
.name { min-width: 84px; }
.detail { color: var(--muted); font-size: 12px; }
</style>
