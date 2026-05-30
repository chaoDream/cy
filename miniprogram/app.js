const { silentLogin } = require('./utils/auth');
const track = require('./utils/track');

App({
  globalData: {
    userInfo: null,
    // 省钱资产库（全局缓存，PRD §5.1）
    assets: {
      vip88: false,
      jdPlus: false,
      pddMonthly: false,
      govSubsidyRegion: '',
    },
  },

  onLaunch() {
    // 恢复本地缓存的资产库勾选
    const cachedAssets = wx.getStorageSync('assets');
    if (cachedAssets) {
      this.globalData.assets = Object.assign(this.globalData.assets, cachedAssets);
    }
    // 静默登录拿到自定义登录态 token
    silentLogin().catch((e) => console.warn('登录失败', e));
    track.event('app_open');
  },

  // 供各页面读取/更新资产库
  getAssets() {
    return this.globalData.assets;
  },

  setAssets(assets) {
    this.globalData.assets = Object.assign({}, this.globalData.assets, assets);
    wx.setStorageSync('assets', this.globalData.assets);
  },
});
