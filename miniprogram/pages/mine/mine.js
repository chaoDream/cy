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
    const { getBaseUrl, getEnv } = require('../../utils/config');
    ensureLogin()
      .then(() => this.setData({ loggedIn: true }))
      .catch((err) => {
        const baseUrl = getBaseUrl();
        console.error('登录失败', { env: getEnv(), baseUrl, err });
        wx.showModal({
          title: '登录失败',
          content: `环境: ${getEnv()}\n地址: ${baseUrl}\n\n${err.message || '网络异常'}\n\n请确认：\n1. 手机与电脑同一 Wi-Fi\n2. 手机浏览器能打开 ${baseUrl}/api/health\n3. iOS 需允许微信「本地网络」权限`,
          showCancel: false,
        });
      });
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
