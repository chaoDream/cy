const { yuan } = require('../../utils/format');

Component({
  properties: {
    price: { type: Object, value: null }, // FinalPriceResult
  },
  data: {
    steps: [],
    expanded: false,
  },
  observers: {
    price(p) {
      if (!p) return;
      const steps = [];
      steps.push({ name: '展示价', amount: yuan(p.displayPrice), type: 'base' });
      (p.included || []).forEach((d) => {
        steps.push({ name: d.name, amount: `- ${yuan(d.amount)}`, type: 'minus' });
      });
      if (Number(p.freight) > 0) {
        steps.push({ name: '运费', amount: `+ ${yuan(p.freight)}`, type: 'plus' });
      }
      steps.push({ name: '参考到手价', amount: yuan(p.estimatedFinalPrice), type: 'final' });
      this.setData({ steps });
    },
  },
  methods: {
    toggle() {
      this.setData({ expanded: !this.data.expanded });
      this.triggerEvent('expand', { expanded: this.data.expanded });
    },
  },
});
