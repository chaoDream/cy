const { platformName, yuanTrim } = require('../../utils/format');

Component({
  properties: {
    item: { type: Object, value: null },
  },
  data: { platformText: '', priceText: '--' },
  observers: {
    item(v) {
      if (!v) return;
      const price = v.bestFinalPrice || v.estimatedFinalPrice || v.rawPrice;
      this.setData({
        platformText: platformName(v.platform),
        priceText: price > 0 ? yuanTrim(price) : '--',
      });
    },
  },
  methods: {
    onTap() {
      this.triggerEvent('tap', this.data.item);
    },
  },
});
