const api = require('../../api/index');
const { ensureLogin } = require('../../utils/auth');
const { getToken } = require('../../utils/request');

const app = getApp();

Page({
  data: {
    loggedIn: false,
    assets: {},
  },

  onShow() {
    this.setData({ loggedIn: !!getToken(), assets: app.getAssets() });
    if (getToken()) {
      api.getProfile().then((p) => {
        if (p.assets) {
          app.setAssets(p.assets);
          this.setData({ assets: app.getAssets() });
        }
      }).catch(() => {});
    }
  },

  onLogin() {
    ensureLogin().then(() => this.setData({ loggedIn: true }));
  },

  onAssetsChange(e) {
    const assets = e.detail;
    this.setData({ assets });
    app.setAssets(assets);
    api.saveAssets(assets).catch(() => {});
  },

  goDataNotice() {
    wx.navigateTo({ url: '/packageA/pages/data-notice/data-notice' });
  },

  goPrivacy() {
    wx.navigateTo({ url: '/packageA/pages/privacy/privacy' });
  },

  goSettings() {
    wx.navigateTo({ url: '/packageA/pages/settings/settings' });
  },

  goFeedback() {
    wx.navigateTo({ url: '/packageA/pages/data-notice/data-notice?mode=feedback' });
  },
});
