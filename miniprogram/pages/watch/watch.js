const api = require('../../api/index');
const track = require('../../utils/track');
const { ensureLogin } = require('../../utils/auth');

Page({
  data: {
    list: [],
    loading: true,
    needLogin: false,
  },

  onShow() {
    this._load();
  },

  onPullDownRefresh() {
    this._load().then(() => wx.stopPullDownRefresh());
  },

  _load() {
    this.setData({ loading: true });
    return ensureLogin()
      .then(() => api.watchList())
      .then((list) => this.setData({ list, loading: false, needLogin: false }))
      .catch((err) => {
        this.setData({ loading: false, needLogin: !!err.needLogin });
      });
  },

  onLogin() {
    ensureLogin().then(() => this._load());
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
