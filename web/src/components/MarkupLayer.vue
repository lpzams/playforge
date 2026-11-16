<script setup lang="ts">
// The frozen game frame over the stage: drag to box up to MAX_BOXES areas, × to remove one.
import { computed, ref } from 'vue';
import { boxFrom, MAX_BOXES, type Box } from '../markup/markup';

const props = defineProps<{ image: string; width: number; height: number; modelValue: Box[] }>();
const emit = defineEmits<{ 'update:modelValue': [boxes: Box[]] }>();

const surface = ref<HTMLElement | null>(null);
const start = ref<{ x: number; y: number; id: number } | null>(null);
const draft = ref<Box | null>(null);
const full = computed(() => props.modelValue.length >= MAX_BOXES);

function point(e: PointerEvent) {
  const r = surface.value!.getBoundingClientRect();
  return { x: (e.clientX - r.left) / r.width, y: (e.clientY - r.top) / r.height };
}

function down(e: PointerEvent) {
  if (full.value || e.button !== 0) return;
  const p = point(e);
  start.value = { ...p, id: e.pointerId };
  surface.value?.setPointerCapture?.(e.pointerId);
  e.preventDefault();
}

function move(e: PointerEvent) {
  if (!start.value || e.pointerId !== start.value.id) return;
  const p = point(e);
  draft.value = boxFrom(start.value.x, start.value.y, p.x, p.y);
}

function up(e: PointerEvent) {
  if (!start.value || e.pointerId !== start.value.id) return;
  const p = point(e);
  const box = boxFrom(start.value.x, start.value.y, p.x, p.y);
  start.value = null;
  draft.value = null;
  if (box && !full.value) emit('update:modelValue', [...props.modelValue, box]);
}

function remove(i: number) {
  emit('update:modelValue', props.modelValue.filter((_, j) => j !== i));
}

const pct = (b: Box) => ({ left: `${b.x * 100}%`, top: `${b.y * 100}%`, width: `${b.w * 100}%`, height: `${b.h * 100}%` });
</script>

<template>
  <div class="markup" data-test="markup-layer">
    <div class="fit" :style="{ '--ar': width / height }">
      <div
        ref="surface"
        class="surface"
        :class="{ full }"
        data-test="markup-surface"
        @pointerdown="down"
        @pointermove="move"
        @pointerup="up"
        @pointercancel="start = null; draft = null"
      >
        <img :src="image" alt="游戏画面截图" draggable="false" />
        <div v-for="(b, i) in modelValue" :key="i" class="box" :class="{ top: b.y < 0.08 }" :style="pct(b)" data-test="markup-box">
          <span class="tag">{{ i + 1 }}</span>
          <button type="button" class="remove" :aria-label="`删除框 ${i + 1}`" data-test="markup-remove" @pointerdown.stop @click="remove(i)">×</button>
        </div>
        <div v-if="draft" class="box draft" :style="pct(draft)" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.markup { position: absolute; inset: 0; background: #111; container-type: size; }
/* The snapshot is the canvas alone: fit it inside the stage (10 px margin like the shim), keeping its ratio. */
.fit { position: absolute; inset: 10px; display: grid; place-items: center; container-type: size; }
.surface {
  position: relative; width: min(100cqw, 100cqh * var(--ar)); aspect-ratio: var(--ar);
  cursor: crosshair; touch-action: none; user-select: none;
}
.surface.full { cursor: default; }
img { position: absolute; inset: 0; width: 100%; height: 100%; display: block; pointer-events: none; }
.box { position: absolute; border: 2px solid #ff2d2d; box-shadow: 0 0 0 1px rgba(0, 0, 0, .35); border-radius: 2px; }
.box.draft { border-style: dashed; }
.box.top .tag { top: 0; left: 0; border-radius: 0 0 4px 0; }
.tag { position: absolute; left: -2px; top: -24px; min-width: 22px; height: 22px; padding: 0 6px; background: #ff2d2d; color: #fff; font-size: 13px; font-weight: 600; line-height: 22px; text-align: center; border-radius: 4px 4px 0 0; }
.remove { position: absolute; right: -11px; top: -11px; width: 22px; height: 22px; border-radius: 50%; border: 0; background: #fff; color: #1f1f1f; font-size: 15px; line-height: 22px; padding: 0; cursor: pointer; box-shadow: 0 1px 3px rgba(0, 0, 0, .4); }
</style>
