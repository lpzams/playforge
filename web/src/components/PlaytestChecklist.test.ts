import { describe, expect, it } from 'vitest';
import { mount } from '@vue/test-utils';
import PlaytestChecklist from './PlaytestChecklist.vue';

describe('PlaytestChecklist', () => {
  it('labels checks in Chinese and marks pass/fail with detail', () => {
    const w = mount(PlaytestChecklist, {
      props: {
        checks: [
          { name: 'build', passed: true, detail: '' },
          { name: 'action:left', passed: false, detail: 'no change' },
          { name: 'not_blank', passed: true, detail: 'gray stddev 18.46' },
        ],
      },
    });
    const rows = w.findAll('[data-test="check"]');
    expect(rows).toHaveLength(3);
    expect(rows[0].text()).toContain('构建');
    expect(rows[0].text()).toContain('✓');
    expect(rows[1].text()).toContain('按键 left');
    expect(rows[1].text()).toContain('✗');
    expect(rows[1].text()).toContain('no change');
    expect(rows[2].text()).toContain('画面非空白');
  });

  it('unknown check names are shown as-is', () => {
    const w = mount(PlaytestChecklist, { props: { checks: [{ name: 'custom_check', passed: false, detail: 'boom' }] } });
    expect(w.text()).toContain('custom_check');
  });
});
