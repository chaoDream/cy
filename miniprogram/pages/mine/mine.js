const api = require('../../api/index');
const track = require('../../utils/track');
const { getUserInfo, restoreLogin } = require('../../utils/auth');
const { getToken } = require('../../utils/request');
const { resolveImageUrl } = require('../../utils/format');

const app = getApp();

Page(track.mergePage({
  data: {
    nickname: '',
    avatarUrl: '',
    loading: true,
    assets: {},
  },

  onShow() {
    this.refreshUserState();
  },

  refreshUserState() {
    this.setData({ loading: true, assets: app.getAssets() });
    const cached = getUserInfo();
    if (getToken()) {
      this.applyDisplay(cached.nickname, cached.avatarUrl);
      this.loadProfile(cached.nickname, cached.avatarUrl);
      return;
    }
    restoreLogin()
      .then((data) => {
        this.applyDisplay(data.nickname, data.avatarUrl);
        return this.loadProfile(data.nickname, data.avatarUrl);
      })
      .catch(() => {
        this.setData({ loading: false, nickname: '', avatarUrl: '' });
      });
  },

  applyDisplay(nickname, avatarPath) {
    this.setData({
      nickname: nickname || '',
      avatarUrl: resolveImageUrl(avatarPath || ''),
      loading: false,
    });
  },

  loadProfile(prevNickname, prevAvatar) {
    if (!getToken()) {
      this.setData({ loading: false });
      return Promise.resolve();
    }
    return api.getProfile().then((p) => {
      if (p.assets) {
        app.setAssets(p.assets);
      }
      const n = p.nickname || prevNickname || '';
      const a = p.avatarUrl || prevAvatar || '';
      const { setUserInfo } = require('../../utils/auth');
      setUserInfo({ nickname: n, avatarUrl: a });
      this.setData({
        assets: app.getAssets(),
        nickname: n,
        avatarUrl: resolveImageUrl(a),
        loading: false,
      });
    }).catch(() => {
      this.setData({ loading: false });
    });
  },

  onAvatarError() {
    this.setData({ avatarUrl: '' });
  },

  onAssetsChange(e) {
    const assets = e.detail;
    this.setData({ assets });
    app.setAssets(assets);
    if (getToken()) {
      api.saveAssets(assets).catch(() => {});
    }
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
}, track.pageMixin('mine')));
