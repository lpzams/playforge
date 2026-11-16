import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import MarkupLayer from './MarkupLayer.vue';
import type { Box } from '../markup/markup';

function layer(boxes: Box[] = []) {
  const w = mount(MarkupLayer, {
    props: { image: 'data:image/png;base64,x', width: 400, height: 200, modelValue: boxes,
      'onUpdate:modelValue': (v: Box[]) => w.setProps({ modelValue: v }) },
  });
  const surface = w.find('[data-test="markup-surface"]');
  (surface.element as HTMLElement).getBoundingClientRect = () => ({ left: 100, top: 50, width: 400, height: 200 }) as DOMRect;
  return { w, surface };
}

async function drag(surface: ReturnType<typeof layer>['surface'], from: [number, number], to: [number, number]) {
  await surface.trigger('pointerdown', { button: 0, pointerId: 1, clientX: from[0], clientY: from[1] });
  await surface.trigger('pointermove', { pointerId: 1, clientX: to[0], clientY: to[1] });
  await surface.trigger('pointerup', { pointerId: 1, clientX: to[0], clientY: to[1] });
}

describe('MarkupLayer', () => {
  it('drag draws a numbered box in canvas-relative coordinates', async () => {
    const { w, surface } = layer();
    await drag(surface, [140, 70], [300, 150]); // 10%..50% x, 10%..50% y
    const boxes = w.props('modelValue');
    expect(boxes).toHaveLength(1);
    expect(boxes[0].x).toBeCloseTo(0.1);
    expect(boxes[0].w).toBeCloseTo(0.4);
    expect(boxes[0].y).toBeCloseTo(0.1);
    expect(boxes[0].h).toBeCloseTo(0.4);
    expect(w.find('[data-test="markup-box"]').text()).toContain('1');
  });

  it('ignores clicks, stops at three boxes and removes one with ×', async () => {
    const { w, surface } = layer();
    await drag(surface, [200, 100], [201, 101]);
    expect(w.props('modelValue')).toHaveLength(0);
    for (let i = 0; i < 4; i++) await drag(surface, [110 + i * 90, 60], [180 + i * 90, 120]);
    expect(w.props('modelValue')).toHaveLength(3);

    await w.findAll('[data-test="markup-remove"]')[0].trigger('click');
    expect(w.props('modelValue')).toHaveLength(2);
    expect(w.findAll('[data-test="markup-box"]').map((b) => b.find('.tag').text())).toEqual(['1', '2']);
  });
});
