<script setup lang="ts">
// A version's generation told as a short story (live while it runs). Everything an AI produced is rendered with
// {{ }} interpolation only: the page shares an origin with the API, so AI output must never become HTML.
import { computed, ref } from 'vue';
import type { RunEvent } from '../api/types';
import { buildStory } from '../story/story';
import EventFeed from './EventFeed.vue';

const props = defineProps<{ events: RunEvent[]; previewBase: string | null; instruction: string; label: string; followUp?: boolean }>();

const story = computed(() => buildStory(props.events, { previewBase: props.previewBase, followUp: props.followUp }));
const showNote = ref(false);
const showLog = ref(false);
</script>

<template>
  <ol class="feed" data-test="story">
    <li class="entry">
      <div class="who user">需</div>
      <div>
        <div class="entry-head"><span class="name">{{ label }}</span></div>
        <p class="entry-text plain-text">{{ instruction }}</p>
      </div>
    </li>

    <li v-for="e in story.entries" :key="e.key" class="entry" data-test="story-entry">
      <div class="who" :class="e.cls">{{ e.mono }}</div>
      <div>
        <div class="entry-head">
          <span class="name">{{ e.actor }}</span>
          <span v-if="e.running" class="live">进行中</span>
          <span v-else>{{ e.time }}</span>
        </div>
        <p v-for="(l, i) in e.lines" :key="i" class="entry-text plain-text" :class="{ bad: e.bad && i === 0 }">{{ l }}</p>
        <div v-for="(q, i) in e.quotes" :key="`q${i}`" class="quote">
          <span v-if="q.loc" class="loc">{{ q.loc }}</span>
          <p class="plain-text">{{ q.text }}</p>
        </div>
        <ul v-if="e.failures.length" class="failed">
          <li v-for="(f, i) in e.failures" :key="i">{{ f.text }}<span v-if="f.detail" class="plain-text">（{{ f.detail }}）</span></li>
        </ul>
        <p v-if="e.checks" class="checklist">{{ e.checks }}</p>
        <template v-if="e.ai">
          <p class="entry-text" :class="{ bad: e.ai.bad }" data-test="ai-lead">{{ e.ai.lead }}</p>
          <ul v-if="e.ai.items.length" class="ai-checks">
            <li v-for="(a, i) in e.ai.items" :key="i" :class="{ fail: !a.passed }" data-test="ai-assertion">{{ a.text }}</li>
          </ul>
          <div v-if="e.ai.shots.length" class="shots">
            <figure v-for="s in e.ai.shots" :key="s.src"><img :src="s.src" :alt="s.label" loading="lazy" /><figcaption>{{ s.label }}</figcaption></figure>
          </div>
        </template>
        <ul v-if="e.hints.length" class="ai-checks hint">
          <li v-for="(h, i) in e.hints" :key="i">{{ h }}</li>
        </ul>
      </div>
    </li>

    <li v-if="story.finished && story.finished !== 'SUCCEEDED'" class="entry">
      <div class="who">·</div>
      <div>
        <div class="entry-head"><span class="name">{{ story.finished === 'CANCELLED' ? '已取消' : '生成失败' }}</span></div>
        <p v-if="story.reason" class="entry-text plain-text" :class="{ bad: story.finished === 'FAILED' }">{{ story.reason }}</p>
      </div>
    </li>

    <li v-if="story.note" class="entry">
      <div class="who claude">C</div>
      <div>
        <div class="entry-head"><span class="name">Claude</span><span>交付说明</span></div>
        <button type="button" class="pill plain more" @click="showNote = !showNote">{{ showNote ? '收起说明' : '查看 Claude 的说明' }}</button>
        <div v-if="showNote" class="note">{{ story.note }}</div>
      </div>
    </li>
  </ol>

  <div class="log">
    <button type="button" class="pill plain more" data-test="toggle-log" @click="showLog = !showLog">
      {{ showLog ? '收起完整日志' : `完整日志（${events.length} 条事件）` }}
    </button>
    <div v-if="showLog" class="log-body"><EventFeed :events="events" /></div>
  </div>
</template>

<style scoped>
.more { height: 30px; padding: 0 12px 0 0; margin-top: 4px; }
.log { margin-top: 4px; padding-left: 44px; }
.log-body { margin-top: 8px; padding: 14px 16px; border-radius: var(--radius); background: var(--surface); max-height: 520px; overflow-y: auto; }
</style>
