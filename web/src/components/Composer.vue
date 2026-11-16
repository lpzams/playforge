<script setup lang="ts">
// One rounded input surface with a round send button (Gemini-style). Used for new games and for follow-ups.
import { computed, nextTick, ref, watch } from 'vue';

const props = withDefaults(defineProps<{
  placeholder: string;
  submitting?: boolean;
  disabled?: boolean;
  maxLength?: number;
  /** data-test of the root; the button gets `${testId}-submit` (or "submit" when no testId). */
  testId?: string;
  examples?: { label: string; text: string }[];
}>(), { submitting: false, disabled: false, maxLength: 4000, testId: '', examples: () => [] });

const emit = defineEmits<{ submit: [text: string] }>();
const text = ref('');
const box = ref<HTMLTextAreaElement | null>(null);

const tooLong = computed(() => text.value.length > props.maxLength);
const canSubmit = computed(() => !props.disabled && !props.submitting && !tooLong.value && text.value.trim().length > 0);

/** Grows with its content up to a few lines, then scrolls. */
function fit() {
  const el = box.value;
  if (!el) return;
  el.style.height = 'auto';
  el.style.height = `${Math.min(el.scrollHeight, 220)}px`;
}
watch(text, () => nextTick(fit));

function submit() {
  if (!canSubmit.value) return;
  emit('submit', text.value.trim());
}

/** Lets the parent clear the box once the submission went through. */
function clear() {
  text.value = '';
}
defineExpose({ clear });
</script>

<template>
  <div class="composer" :class="{ disabled }" :data-test="testId || undefined">
    <textarea
      ref="box"
      v-model="text"
      rows="2"
      :placeholder="placeholder"
      :disabled="disabled"
      @keydown.ctrl.enter.prevent="submit"
      @keydown.meta.enter.prevent="submit"
    />
    <div class="row">
      <div class="examples">
        <button v-for="(ex, i) in examples" :key="i" type="button" class="example" data-test="example" @click="text = ex.text">
          {{ ex.label }}
        </button>
        <slot name="left" />
      </div>
      <span v-if="tooLong" class="error-text">太长了（最多 {{ maxLength }} 字）</span>
      <slot name="right" />
      <button
        type="button"
        class="send"
        :disabled="!canSubmit"
        :aria-label="submitting ? '提交中' : '发送'"
        :data-test="testId ? `${testId}-submit` : 'submit'"
        @click="submit"
      >
        <svg v-if="!submitting" viewBox="0 0 24 24" width="20" height="20" aria-hidden="true"><path d="M12 19V5m0 0-6 6m6-6 6 6" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>
        <span v-else class="spin" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.composer { background: var(--surface); border-radius: 28px; padding: 16px 16px 12px 22px; transition: background .15s; }
.composer:focus-within { background: var(--surface-hi); }
.composer.disabled { opacity: .7; }
textarea { width: 100%; border: 0; outline: 0; resize: none; background: transparent; font: inherit; font-size: 16px; line-height: 1.6; color: var(--text); padding: 0; }
textarea::placeholder { color: var(--muted); }
.row { display: flex; align-items: center; gap: 10px; margin-top: 8px; }
.examples { display: flex; flex-wrap: wrap; gap: 8px; margin-right: auto; }
.example { height: 32px; padding: 0 14px; border-radius: 999px; border: 0; background: #fff; color: var(--text-2); font-size: 13.5px; cursor: pointer; }
.example:hover { background: var(--tonal); color: var(--on-tonal); }
.send { flex: none; width: 44px; height: 44px; border-radius: 50%; border: 0; background: var(--accent); color: #fff; display: grid; place-items: center; cursor: pointer; }
.send:disabled { background: #c4c7c5; cursor: default; }
.spin { width: 18px; height: 18px; border-radius: 50%; border: 2px solid rgba(255, 255, 255, .5); border-top-color: #fff; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
