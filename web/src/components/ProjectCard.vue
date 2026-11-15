<script setup lang="ts">
import { computed } from 'vue';
import type { ProjectView } from '../api/types';
import StatusTag from './StatusTag.vue';

const props = defineProps<{ project: ProjectView }>();

const latest = computed(() => props.project.runs[props.project.runs.length - 1]);
const created = computed(() => new Date(props.project.createdAt).toLocaleString('zh-CN', { hour12: false }));
</script>

<template>
  <router-link :to="`/projects/${project.id}`" class="card-link" data-test="project-card">
    <el-card shadow="hover" class="card">
      <div class="title">{{ project.title }}</div>
      <div class="meta">
        <StatusTag v-if="latest" :status="latest.status" />
        <el-tag v-if="project.previewAvailable" size="small" type="success" effect="plain">可试玩</el-tag>
        <span class="muted time">{{ created }}</span>
      </div>
      <div v-if="project.runs.length > 1" class="muted runs">共 {{ project.runs.length }} 次生成 / 修改</div>
    </el-card>
  </router-link>
</template>

<style scoped>
.card-link { text-decoration: none; color: inherit; }
.card { height: 100%; }
.title { font-size: 15px; font-weight: 600; margin-bottom: 10px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.time { margin-left: auto; font-size: 12px; }
.runs { margin-top: 8px; font-size: 12px; }
</style>
