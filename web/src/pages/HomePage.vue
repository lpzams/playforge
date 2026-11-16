<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api/client';
import type { ProjectView } from '../api/types';
import Composer from '../components/Composer.vue';
import ProjectCard from '../components/ProjectCard.vue';

const EXAMPLES = [
  { label: '躲陨石', text: '一个躲陨石的太空飞船小游戏：方向键左右移动飞船，陨石从上往下落，撞到陨石游戏结束，显示得分，按空格重新开始' },
  { label: '贪吃蛇', text: '一个贪吃蛇小游戏：方向键控制蛇移动，吃到食物蛇身变长并加分，撞墙或撞到自己游戏结束，显示得分，按空格重新开始' },
  { label: '打砖块', text: '一个打砖块小游戏：左右方向键移动挡板，球碰到砖块砖块消失并加分，球掉下去游戏结束，按空格重新开始' },
];

const router = useRouter();
const submitting = ref(false);
const error = ref('');
const projects = ref<ProjectView[]>([]);
const loading = ref(true);
const query = ref('');

const shown = computed(() => {
  const q = query.value.trim().toLowerCase();
  return [...projects.value]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .filter((p) => !q || p.title.toLowerCase().includes(q) || p.prompt.toLowerCase().includes(q));
});

onMounted(async () => {
  try {
    projects.value = await api.listProjects();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
});

async function create(prompt: string) {
  if (submitting.value) return;
  submitting.value = true;
  error.value = '';
  try {
    const created = await api.createProject(prompt);
    await router.push(`/projects/${created.projectId}`);
  } catch (e) {
    error.value = (e as Error).message;
    submitting.value = false;
  }
}
</script>

<template>
  <div class="home">
    <section class="hero">
      <h1>想玩个什么游戏？</h1>
      <p class="lead">一句话描述就行。Claude 写代码，Codex 审查，AI 试玩员亲自玩过一局才交给你。</p>
      <Composer
        placeholder="描述你想要的小游戏，例如：一个躲陨石的太空飞船游戏，方向键移动，撞到就结束（Ctrl + Enter 发送）"
        :submitting="submitting"
        :examples="EXAMPLES"
        @submit="create"
      />
      <p v-if="error" class="error-text">{{ error }}</p>
    </section>

    <section class="mine">
      <div class="mine-head">
        <h2>我的游戏</h2>
        <span class="muted">{{ shown.length }} 个</span>
      </div>
      <label v-if="projects.length > 0" class="search">
        <svg viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><circle cx="11" cy="11" r="7" fill="none" stroke="currentColor" stroke-width="2"/><path d="m20 20-3.5-3.5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>
        <input v-model="query" type="search" placeholder="搜索游戏" data-test="search" />
      </label>

      <p v-if="loading" class="muted">加载中…</p>
      <p v-else-if="projects.length === 0" class="muted empty">还没有游戏。在上面描述一个，几分钟后就能玩。</p>
      <p v-else-if="shown.length === 0" class="muted empty">没有匹配的游戏</p>
      <div v-else class="cards">
        <ProjectCard v-for="p in shown" :key="p.id" :project="p" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.home { max-width: 1200px; margin: 0 auto; padding: 0 24px 64px; }
.hero { max-width: 820px; margin: 56px auto 0; }
.hero h1 { font-size: 44px; line-height: 1.15; margin: 0 0 12px; letter-spacing: -.5px; }
.lead { margin: 0 0 24px; font-size: 16px; color: var(--text-2); }
.hero .error-text { margin: 10px 4px 0; }
.mine { margin-top: 64px; }
.mine-head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 16px; }
.mine-head h2 { font-size: 22px; margin: 0; }
.search { margin-bottom: 20px; }
.empty { padding: 24px 0; }
.cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
@media (max-width: 960px) { .cards { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) {
  .cards { grid-template-columns: 1fr; }
  .hero { margin-top: 32px; }
  .hero h1 { font-size: 34px; }
}
</style>
