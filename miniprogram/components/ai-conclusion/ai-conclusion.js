Component({
  properties: {
    rec: { type: Object, value: null },
    loading: { type: Boolean, value: false },
    error: { type: String, value: '' },
  },
  data: {
    themeMap: {
      buy: 'buy',
      wait: 'wait',
      caution: 'caution',
      avoid: 'avoid',
    },
  },
  methods: {
    onRetry() {
      this.triggerEvent('retry');
    },
  },
});
