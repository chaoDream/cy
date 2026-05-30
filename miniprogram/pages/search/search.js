const api = require('../../api/index');
const track = require('../../utils/track');
const { detectFromClipboard } = require('../../utils/clipboard');

const app = getApp();

Page({
  data: {
    linkText: '',
    assets: {},
    recent: [],
    searchResults: [],
    clipboardHint: '',
  },

  onLoad() {
    this.setData({
      assets: app.getAssets(),
      recent: wx.getStorageSync('recentQueries') || [],
    });
  },

  onShow() {
    // 剪贴板检测：检测到京东/拼多多链接则提示一键查价（PRD §7.1）
    detectFromClipboard().then((text) => {
      if (text && text !== this.data.linkText) {
        this.setData({ clipboardHint: text });
        track.event('link_detected', { source: 'clipboard' });
      }
    });
  },

  onInput(e) {
    this.setData({ linkText: e.detail.value });
  },

  useClipboard() {
    this.setData({ linkText: this.data.clipboardHint, clipboardHint: '' });
    this.onParse();
  },

  dismissClipboard() {
    this.setData({ clipboardHint: '' });
  },

  onAssetsChange(e) {
    const assets = e.detail;
    this.setData({ assets });
    app.setAssets(assets);
    // 已登录则同步到服务端档案
    api.saveAssets(assets).catch(() => {});
  },

  onParse() {
    const linkText = (this.data.linkText || '').trim();
    if (!linkText) {
      wx.showToast({ title: '请粘贴京东或拼多多手机商品链接', icon: 'none' });
      return;
    }
    track.event('link_parse_submit');
    api
      .parseLink(linkText)
      .then((res) => {
        track.event('link_parse_success', { platform: res.platform });
        this._saveRecent(res);
        this._gotoAnalysis(res.platform, res.itemId);
      })
      .catch((err) => {
        track.event('link_parse_fail', { code: err.code });
        wx.showToast({ title: err.message || '解析失败', icon: 'none' });
      });
  },

  onSearchInput(e) {
    this.setData({ keyword: e.detail.value });
  },

  onSearch() {
    const keyword = (this.data.keyword || '').trim();
    if (!keyword) return;
    api.search(keyword).then((list) => this.setData({ searchResults: list }));
  },

  onCardTap(e) {
    const item = e.detail;
    this._gotoAnalysis(item.platform, item.itemId);
  },

  onRecentTap(e) {
    const { platform, itemid } = e.currentTarget.dataset;
    this._gotoAnalysis(platform, itemid);
  },

  _gotoAnalysis(platform, itemId) {
    wx.navigateTo({
      url: `/packageA/pages/analysis/analysis?platform=${platform}&itemId=${itemId}`,
    });
  },

  _saveRecent(res) {
    const recent = this.data.recent.filter(
      (r) => !(r.platform === res.platform && r.itemId === res.itemId),
    );
    recent.unshift({
      platform: res.platform,
      itemId: res.itemId,
      title: res.productInfo.title,
      imageUrl: res.productInfo.imageUrl,
    });
    const next = recent.slice(0, 10);
    this.setData({ recent: next });
    wx.setStorageSync('recentQueries', next);
  },
});
