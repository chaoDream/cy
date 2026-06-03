const { restoreLogin } = require('./utils/auth');
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
    const cachedAssets = wx.getStorageSync('assets');
    if (cachedAssets) {
      this.globalData.assets = Object.assign(this.globalData.assets, cachedAssets);
    }
    // 静默 wx.login：恢复或创建登录态（openid + token，昵称由服务端固定绑定）
    restoreLogin().catch(() => {});
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
