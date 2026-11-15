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

const tooLong = computed(() => prompt.value.length > MAX_PROMPT);
const canSubmit = computed(() => prompt.value.trim().length > 0 && !tooLong.value && !submitting.value);
const sorted = computed(() => [...projects.value].sort((a, b) => b.createdAt.localeCompare(a.createdAt)));

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
    <section class="create">
      <h2>描述你想要的小游戏</h2>
      <el-input
        v-model="prompt"
        type="textarea"
        :rows="4"
        resize="none"
        placeholder="例如：一个躲陨石的太空飞船游戏，方向键移动，撞到就结束"
        @keydown.ctrl.enter="submit"
      />
      <div class="actions">
        <div class="examples">
          <span class="muted">试试：</span>
          <el-button
            v-for="(ex, i) in EXAMPLES"
            :key="i"
            link
            type="primary"
            data-test="example"
            @click="prompt = ex"
          >{{ ex.split('：')[0] }}</el-button>
        </div>
        <span v-if="tooLong" class="error">需求太长了（最多 {{ MAX_PROMPT }} 字）</span>
        <el-button type="primary" :disabled="!canSubmit" :loading="submitting" data-test="submit" @click="submit">
          生成
        </el-button>
      </div>
      <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="alert" />
    </section>

    <section class="list">
      <h3>我的游戏</h3>
      <el-skeleton v-if="loading" :rows="3" animated />
      <el-empty v-else-if="sorted.length === 0" description="还没有游戏，先在上面描述一个吧" />
      <div v-else class="grid">
        <ProjectCard v-for="p in sorted" :key="p.id" :project="p" />
      </div>
    </section>
  </div>
</template>

<style scoped>
.home { max-width: 960px; margin: 0 auto; }
.create { background: #fff; border: 1px solid var(--pf-border); border-radius: 8px; padding: 20px 24px; }
h2 { margin: 0 0 12px; font-size: 18px; }
h3 { margin: 28px 0 12px; font-size: 15px; }
.actions { display: flex; align-items: center; gap: 12px; margin-top: 12px; }
.examples { display: flex; align-items: center; flex-wrap: wrap; gap: 4px; margin-right: auto; }
.error { color: var(--el-color-danger); }
.alert { margin-top: 12px; }
.grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: 16px; }
</style>
