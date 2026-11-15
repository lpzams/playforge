<script setup lang="ts">
import { computed, ref } from 'vue';

const props = defineProps<{ active: boolean; submitting: boolean }>();
const emit = defineEmits<{ submit: [text: string]; cancel: [] }>();

const text = ref('');
const canSubmit = computed(() => !props.active && !props.submitting && text.value.trim().length > 0);

function submit() {
  if (!canSubmit.value) return;
  emit('submit', text.value.trim());
  text.value = '';
}
</script>

<template>
  <div class="follow-up" data-test="follow-up">
    <el-input
      v-model="text"
      type="textarea"
      :rows="2"
      resize="none"
      :disabled="active"
      :placeholder="active ? '正在生成中，完成后可以继续修改' : '继续修改，例如：敌人再快一点、加一个计分板'"
      @keydown.ctrl.enter="submit"
    />
    <div class="buttons">
      <el-button v-if="active" data-test="cancel" @click="emit('cancel')">取消本次生成</el-button>
      <el-button type="primary" :disabled="!canSubmit" :loading="submitting" data-test="follow-up-submit" @click="submit">
        继续修改
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.follow-up { display: flex; gap: 10px; align-items: flex-end; background: #fff; border: 1px solid var(--pf-border); border-radius: 6px; padding: 10px; }
.buttons { display: flex; flex-direction: column; gap: 6px; }
.buttons .el-button + .el-button { margin-left: 0; }
</style>
