const PROVINCES = ['', '北京市', '上海市', '广东省', '江苏省', '浙江省', '四川省', '湖北省', '山东省', '河南省'];

Component({
  properties: {
    assets: {
      type: Object,
      value: { vip88: false, jdPlus: false, pddMonthly: false, govSubsidyRegion: '' },
    },
  },

  data: {
    provinces: PROVINCES,
    regionIndex: 0,
  },

  observers: {
    assets(val) {
      const idx = PROVINCES.indexOf(val.govSubsidyRegion || '');
      this.setData({ regionIndex: idx >= 0 ? idx : 0 });
    },
  },

  methods: {
    onToggle(e) {
      const key = e.currentTarget.dataset.key;
      const next = Object.assign({}, this.data.assets);
      next[key] = !next[key];
      this._emit(next);
    },

    onRegionChange(e) {
      const idx = Number(e.detail.value);
      const next = Object.assign({}, this.data.assets, { govSubsidyRegion: PROVINCES[idx] });
      this.setData({ regionIndex: idx });
      this._emit(next);
    },

    _emit(next) {
      this.setData({ assets: next });
      this.triggerEvent('change', next);
    },
  },
});
