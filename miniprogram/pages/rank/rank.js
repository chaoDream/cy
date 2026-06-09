const api = require('../../api/index');

Page({
  data: {
    brands: ['全部', 'Apple', '华为', '小米', 'vivo', 'OPPO', '荣耀'],
    brandIndex: 0,
    list: [],
    loading: true,
  },

  onLoad() {
    this._load();
  },

  onPullDownRefresh() {
    this._load().then(() => wx.stopPullDownRefresh());
  },

  onBrandTap(e) {
    const idx = Number(e.currentTarget.dataset.idx);
    this.setData({ brandIndex: idx });
    this._load();
  },

  _load() {
    this.setData({ loading: true });
    const brand = this.data.brandIndex === 0 ? '' : this.data.brands[this.data.brandIndex];
    return api
      .rank({ brand })
      .then((list) => this.setData({ list, loading: false }))
      .catch(() => this.setData({ loading: false }));
  },

  onCardTap(e) {
    const item = e.detail;
    if (!item || !item.platform || !item.platformItemId) return;
    wx.navigateTo({
      url: `/packageA/pages/analysis/analysis?platform=${item.platform}&itemId=${item.platformItemId}`,
    });
  },
});
