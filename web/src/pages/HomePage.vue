<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '../api/client';
import type { ProjectView } from '../api/types';
import ProjectCard from '../components/ProjectCard.vue';

const MAX_PROMPT = 4000;
const EXAMPLES = [
  '一个躲陨石的太空飞船小游戏：方向键左右移动飞船，陨石从上往下落，撞到陨石游戏结束，显示得分，按空格重新开始',
  '一个贪吃蛇小游戏：方向键控制，吃到食物变长，撞墙或撞到自己结束，空格重新开始',
  '一个打砖块小游戏：左右方向键移动挡板，球碰到砖块砖块消失并加分，球掉下去游戏结束，按空格重新开始',
];

const router = useRouter();
const prompt = ref('');
const submitting = ref(false);
const error = ref('');
const projects = ref<ProjectView[]>([]);
const loading = ref(true);
const query = ref('');

const tooLong = computed(() => prompt.value.length > MAX_PROMPT);
const canSubmit = computed(() => prompt.value.trim().length > 0 && !tooLong.value && !submitting.value);
const shown = computed(() => {
  const q = query.value.trim().toLowerCase();
  return [...projects.value]
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt))
    .filter((p) => !q || p.title.toLowerCase().includes(q) || p.prompt.toLowerCase().includes(q));
});
const playable = computed(() => projects.value.filter((p) => p.previewAvailable).length);

onMounted(async () => {
  try {
    projects.value = await api.listProjects();
  } catch (e) {
    error.value = (e as Error).message;
  } finally {
    loading.value = false;
  }
});

async function submit() {
  if (!canSubmit.value) return;
  submitting.value = true;
  error.value = '';
  try {
    const created = await api.createProject(prompt.value.trim());
    await router.push(`/projects/${created.projectId}`);
  } catch (e) {
    error.value = (e as Error).message;
    submitting.value = false;
  }
}
</script>

<template>
  <div class="home">
    <section class="create panel">
      <h1>一句话，生成<em>真的能玩</em>的小游戏</h1>
      <p class="muted sub">Claude 写代码，Codex 审查，AI 在浏览器里试玩，发现问题自动修复。</p>
      <el-input
        v-model="prompt"
        type="textarea"
        :rows="3"
        resize="none"
        placeholder="描述你想要的小游戏，例如：一个躲陨石的太空飞船游戏，方向键移动，撞到就结束"
        @keydown.ctrl.enter="submit"
      />
      <div class="actions">
        <div class="examples">
          <span class="muted">试试：</span>
          <button v-for="(ex, i) in EXAMPLES" :key="i" type="button" class="chip" data-test="example" @click="prompt = ex">
            {{ ex.split('：')[0].replace(/^一个/, '') }}
          </button>
        </div>
        <span v-if="tooLong" class="error">需求太长了（最多 {{ MAX_PROMPT }} 字）</span>
        <span class="muted hint">Ctrl + Enter</span>
        <el-button type="primary" size="large" :disabled="!canSubmit" :loading="submitting" data-test="submit" @click="submit">
          生成游戏
        </el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="alert" />
    </section>

    <section class="list">
      <div class="list-head">
        <div>
          <h2>我的游戏</h2>
          <div class="muted counts">{{ projects.length }} 个游戏 · {{ playable }} 个可试玩</div>
        </div>
        <el-input v-model="query" class="search" placeholder="搜索游戏" clearable data-test="search">
          <template #prefix><span class="search-icon">⌕</span></template>
        </el-input>
      </div>
      <el-skeleton v-if="loading" :rows="3" animated />
      <el-empty v-else-if="projects.length === 0" description="还没有游戏，先在上面描述一个吧" />
      <el-empty v-else-if="shown.length === 0" description="没有匹配的游戏" />
      <div v-else class="grid">
        <ProjectCard v-for="p in shown" :key="p.id" :project="p" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.home { max-width: 1120px; margin: 0 auto; }
.create { padding: 28px 28px 22px; background: radial-gradient(900px 260px at 90% -20%, #eef2ff 0%, #fff 60%); }
h1 { margin: 0 0 6px; font-size: 26px; color: var(--pf-text); letter-spacing: -.3px; }
h1 em { font-style: normal; background: linear-gradient(90deg, #2563eb, #7c3aed); -webkit-background-clip: text; background-clip: text; color: transparent; }
.sub { margin: 0 0 16px; }
.actions { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.examples { display: flex; align-items: center; flex-wrap: wrap; gap: 6px; margin-right: auto; }
.chip { border: 1px solid var(--pf-border); background: #fff; border-radius: 999px; padding: 3px 12px; font: inherit; font-size: 13px; color: var(--pf-body); cursor: pointer; }
.chip:hover { border-color: var(--pf-primary); color: var(--pf-primary); }
.hint { font-size: 12px; }
.error { color: var(--el-color-danger); }
.alert { margin-top: 12px; }
.list { margin-top: 32px; }
.list-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
h2 { margin: 0; font-size: 20px; color: var(--pf-text); }
.counts { font-size: 13px; }
.search { width: 280px; }
.search-icon { font-size: 16px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 18px; }
</style>
