const api = require('../../api/index');
const track = require('../../utils/track');
const { ensureLogin } = require('../../utils/auth');
const { yuanTrim } = require('../../utils/format');
const { prepareListImages } = require('../../utils/image');

Page(track.mergePage({
  data: {
    list: [],
    notifyAll: true,
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
      .then((list) => {
        const items = (list || []).map((it) => ({
          ...it,
          originalPriceText: yuanTrim(it.originalPrice),
          currentPriceText: yuanTrim(it.currentPrice),
          targetPriceText: yuanTrim(it.targetPrice),
        }));
        return prepareListImages(items).then((prepared) => {
          this.setData({
            list: prepared,
            notifyAll: prepared.length > 0 && prepared.some((it) => it.notifyEnabled),
            loading: false,
            loadError: '',
          });
        });
      })
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

  // 全局开关：一次性开/关所有盯价的降价提醒
  onToggleNotifyAll(e) {
    const enabled = e.detail.value;
    // 打开：直接开，无需确认
    if (enabled) {
      this._applyNotifyAll(true);
      return;
    }
    // 关闭：二次确认，避免误关导致错过降价
    wx.showModal({
      title: '确认要关闭提醒吗？',
      content: '关闭后所有盯价商品都无法推送消息，无法让您及时获取降价信息。',
      cancelText: '取消',
      confirmText: '确认关闭',
      confirmColor: '#fa3534',
      success: (res) => {
        if (res.confirm) {
          this._applyNotifyAll(false);
        } else {
          this.setData({ notifyAll: true }); // 取消：开关弹回开启态
        }
      },
      fail: () => this.setData({ notifyAll: true }),
    });
  },

  // 提交全局开关，仅更新本地开关态，不整列表重载（避免闪动）
  _applyNotifyAll(enabled) {
    this.setData({ notifyAll: enabled });
    api.setNotifyAll(enabled)
      .then(() => {
        track.event('watch_notify_all', { enabled });
        wx.showToast({ title: enabled ? '已开启降价提醒' : '已关闭全部提醒', icon: 'none' });
      })
      .catch((err) => {
        this.setData({ notifyAll: !enabled }); // 失败回滚
        wx.showToast({ title: (err && err.message) || '设置失败', icon: 'none' });
      });
  },

  onSwitchMode(e) {
    const { watchid, mode, index } = e.currentTarget.dataset;
    wx.showActionSheet({
      itemList: ['只盯当前商家这个价', '盯全平台同款最低价'],
      success: (res) => {
        const next = res.tapIndex === 1 ? 'platform_lowest' : 'merchant';
        if (next === mode) return;
        api.updateWatchMode(watchid, next)
          .then(() => {
            // 只就地更新这一项，不重载整列表（避免闪动）
            this.setData({ [`list[${index}].watchMode`]: next });
          })
          .catch((err) => wx.showToast({ title: (err && err.message) || '切换失败', icon: 'none' }));
      },
    });
  },

  onRemove(e) {
    const { watchid } = e.currentTarget.dataset;
    wx.showModal({
      title: '删除盯价',
      content: '确定删除这个盯价吗？',
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
}, track.pageMixin('watch')));
