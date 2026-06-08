const api = require('../../api/index');
const track = require('../../utils/track');
const { ensureLogin } = require('../../utils/auth');
const { yuanTrim } = require('../../utils/format');

Page({
  data: {
    list: [],
    loading: true,
    loadError: '',
  },

  onShow() {
    this._load();
  },

  onPullDownRefresh() {
    this._load().then(() => wx.stopPullDownRefresh());
  },

  _load() {
    this.setData({ loading: true, loadError: '' });
    return ensureLogin()
      .then(() => api.watchList())
      .then((list) => this.setData({
        list: (list || []).map((it) => ({
          ...it,
          originalPriceText: yuanTrim(it.originalPrice),
          currentPriceText: yuanTrim(it.currentPrice),
          targetPriceText: yuanTrim(it.targetPrice),
        })),
        loading: false,
        loadError: '',
      }))
      .catch((err) => {
        this.setData({
          loading: false,
          loadError: (err && err.message) || '加载失败，请下拉重试',
        });
      });
  },

  onEditTarget(e) {
    const { watchid, current } = e.currentTarget.dataset;
    wx.showModal({
      title: '修改目标价',
      editable: true,
      placeholderText: String(current || ''),
      success: (res) => {
        if (res.confirm && res.content) {
          const target = Number(res.content);
          if (Number.isNaN(target)) return;
          api.updateTarget(watchid, target).then(() => {
            track.event('target_price_update');
            this._load();
          });
        }
      },
    });
  },

  onToggleNotify(e) {
    const { watchid, enabled } = e.currentTarget.dataset;
    api.toggleNotify(watchid, !enabled).then(() => this._load());
  },

  onSwitchMode(e) {
    const { watchid, mode } = e.currentTarget.dataset;
    wx.showActionSheet({
      itemList: ['只盯当前商家这个价', '盯全平台同款最低价'],
      success: (res) => {
        const next = res.tapIndex === 1 ? 'platform_lowest' : 'merchant';
        if (next === mode) return;
        api.updateWatchMode(watchid, next)
          .then(() => this._load())
          .catch((err) => wx.showToast({ title: (err && err.message) || '切换失败', icon: 'none' }));
      },
    });
  },

  onRemove(e) {
    const { watchid } = e.currentTarget.dataset;
    wx.showModal({
      title: '取消盯价',
      content: '确定不再盯这个商品了吗？',
      success: (res) => {
        if (res.confirm) api.removeWatch(watchid).then(() => this._load());
      },
    });
  },

  onTapItem(e) {
    const { platform, itemid } = e.currentTarget.dataset;
    if (platform && itemid) {
      wx.navigateTo({ url: `/packageA/pages/analysis/analysis?platform=${platform}&itemId=${itemid}` });
    }
  },
});
