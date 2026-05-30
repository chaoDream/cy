const { yuan } = require('../../utils/format');

Component({
  properties: {
    trend: { type: Object, value: null },
  },
  data: {
    bars: [],
    low30: '--',
    low90: '--',
  },
  observers: {
    trend(t) {
      if (!t) return;
      const points = t.points || [];
      if (points.length === 0) {
        this.setData({ bars: [], low30: yuan(t.low30), low90: yuan(t.low90) });
        return;
      }
      const prices = points.map((p) => Number(p.price));
      const min = Math.min(...prices);
      const max = Math.max(...prices);
      const range = max - min || 1;
      const bars = points.map((p) => ({
        date: p.date.slice(5),
        price: yuan(p.price),
        // 高度比例 30% ~ 100%
        height: 30 + Math.round(((Number(p.price) - min) / range) * 70),
      }));
      this.setData({ bars, low30: yuan(t.low30), low90: yuan(t.low90) });
    },
  },
});
