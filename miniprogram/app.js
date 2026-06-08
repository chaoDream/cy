const { restoreLogin } = require('./utils/auth');
const track = require('./utils/track');

App({
  globalData: {
    userInfo: null,
    assets: {
      vip88: false,
      jdPlus: false,
      pddMonthly: false,
      govSubsidyRegion: '',
    },
    clipboardPrivacyOk: false,
    pendingPurchase: null,
  },

  onLaunch() {
    const cachedAssets = wx.getStorageSync('assets');
    if (cachedAssets) {
      this.globalData.assets = Object.assign(this.globalData.assets, cachedAssets);
    }
    // 清掉历史遗留的剪贴板授权假标记：授权状态以微信 getPrivacySetting 为准
    try {
      wx.removeStorageSync('__clipboard_privacy_ok__');
    } catch (e) {
      // ignore
    }
    this.globalData.clipboardPrivacyOk = false;
    restoreLogin().catch(() => {});
    track.event('app_open');
  },

  getAssets() {
    return this.globalData.assets;
  },

  setAssets(assets) {
    this.globalData.assets = Object.assign({}, this.globalData.assets, assets);
    wx.setStorageSync('assets', this.globalData.assets);
  },
});
