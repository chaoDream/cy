const api = require('../../api/index');
const { loginWithProfile, getUserInfo } = require('../../utils/auth');
const { getToken } = require('../../utils/request');
const { resolveImageUrl } = require('../../utils/format');

const app = getApp();

Page({
  data: {
    loggedIn: false,
    hasProfile: false,
    nickname: '',
    avatarUrl: '',
    assets: {},
  },

  onShow() {
    this.refreshUserState();
  },

  refreshUserState() {
    const loggedIn = !!getToken();
    const cached = getUserInfo();
    this.setData({
      loggedIn,
      hasProfile: !!(cached.nickname && cached.avatarUrl),
      nickname: cached.nickname || '',
      avatarUrl: resolveImageUrl(cached.avatarUrl),
      assets: app.getAssets(),
    });
    if (loggedIn) {
      api.getProfile().then((p) => {
        if (p.assets) {
          app.setAssets(p.assets);
        }
        const nickname = p.nickname || cached.nickname || '';
        const avatarPath = p.avatarUrl || cached.avatarUrl || '';
        if (nickname || avatarPath) {
          const { setUserInfo } = require('../../utils/auth');
          setUserInfo({ nickname, avatarUrl: avatarPath });
        }
        this.setData({
          assets: app.getAssets(),
          nickname,
          avatarUrl: resolveImageUrl(avatarPath),
          hasProfile: !!(nickname && avatarPath),
        });
      }).catch(() => {});
    }
  },

  onAvatarError() {
    this.setData({ avatarUrl: '', hasProfile: false });
  },

  onLogin() {
    const { getBaseUrl, getEnv, getApiTarget } = require('../../utils/config');
    loginWithProfile()
      .then((data) => {
        this.setData({
          loggedIn: true,
          hasProfile: true,
          nickname: data.nickname || '',
          avatarUrl: resolveImageUrl(data.avatarUrl || ''),
        });
      })
      .catch((err) => {
        if (err.code === -2) {
          wx.showToast({ title: err.message || '已取消授权', icon: 'none' });
          return;
        }
        const baseUrl = getBaseUrl();
        const isRemote = getApiTarget() === 'remote';
        console.error('登录失败', { env: getEnv(), baseUrl, err });
        const tips = isRemote
          ? `请确认：\n1. 远程服务已启动（${baseUrl}/api/health）\n2. 服务器 deploy/.env 已配置微信 AppID/Secret\n3. 开发者工具已勾选「不校验合法域名」`
          : `请确认：\n1. 手机与电脑同一 Wi-Fi\n2. 本地 Docker + 后端已启动\n3. 浏览器能打开 ${baseUrl}/api/health`;
        wx.showModal({
          title: '登录失败',
          content: `地址: ${baseUrl}\n\n${err.message || '网络异常'}\n\n${tips}`,
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
