const { platformName } = require('../../utils/format');

Component({
  properties: {
    item: { type: Object, value: null },
  },
  data: { platformText: '' },
  observers: {
    item(v) {
      if (v) this.setData({ platformText: platformName(v.platform) });
    },
  },
  methods: {
    onTap() {
      this.triggerEvent('tap', this.data.item);
    },
  },
});
