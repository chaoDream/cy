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
  },

  onLaunch() {
    const cachedAssets = wx.getStorageSync('assets');
    if (cachedAssets) {
      this.globalData.assets = Object.assign(this.globalData.assets, cachedAssets);
    }
    this.globalData.clipboardPrivacyOk = !!wx.getStorageSync('__clipboard_privacy_ok__');
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
