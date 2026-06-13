const api = require('../../api/index');
const track = require('../../utils/track');
const { detectFromClipboard } = require('../../utils/clipboard');
const { resolveImageUrl } = require('../../utils/format');
const { prepareImageForDisplay, prepareListImages } = require('../../utils/image');

const app = getApp();

const RECENT_VISIBLE_COUNT = 6; // 首页最多展示2行（3列×2行）
const RECENT_STORAGE_LIMIT = 50;
const RECENT_PAGE_SIZE = 12;
const RECOMMEND_PAGE_SIZE = 6;

Page(track.mergePage({
  data: {
    linkText: '',
    assets: {},
    recent: [],
    recentVisible: [],
    recentHasMore: false,
    searchResults: [],
    clipboardHint: '',
    // 最近查询弹窗
    showRecentPopup: false,
    recentPopupList: [],
    recentPopupPage: 1,
    recentPopupHasMore: false,
    recentPopupLoading: false,
    // 推荐
    _recommendAll: [],
    recommendList: [],
    recommendLoading: false,
    recommendNoMore: false,
  },

  onLoad() {
    this.setData({ assets: app.getAssets() });
    this._loadRecent();
    this._loadRecommend();
  },

  /** 历史图存相对路径；展示时按当前 API 地址（真机/远程）重新拼完整 URL */
  _loadRecent() {
    const raw = wx.getStorageSync('recentQueries') || [];
    const normalized = raw.map((r) => ({
      platform: r.platform,
      itemId: r.itemId,
      title: r.title,
      imageUrl: this._toRelativeImage(r.imageUrl),
    }));
    if (normalized.length && JSON.stringify(normalized) !== JSON.stringify(raw)) {
      wx.setStorageSync('recentQueries', normalized);
    }
    prepareListImages(
      normalized.map((r) => ({ ...r, _imgErr: false })),
    ).then((recent) => {
      const recentVisible = recent.slice(0, RECENT_VISIBLE_COUNT);
      this.setData({ recent, recentVisible, recentHasMore: recent.length > RECENT_VISIBLE_COUNT });
    });
  },

  _loadRecommend() {
    api
      .recommend()
      .then((list) => {
        if (!list || !list.length) return;
        const all = list.map((r) => ({ ...r, itemId: r.platformItemId, _imgErr: false }));
        this._recommendAllRaw = all;
        this._recommendPage = 0;
        this.setData({ recommendList: [], recommendNoMore: false });
        this._loadMoreRecommend();
      })
      .catch(() => {});
  },

  _loadMoreRecommend() {
    if (this.data.recommendLoading || this.data.recommendNoMore) return;
    const all = this._recommendAllRaw || [];
    const page = (this._recommendPage || 0) + 1;
    const end = page * RECOMMEND_PAGE_SIZE;
    const slice = all.slice(this.data.recommendList.length, end);
    if (!slice.length) {
      this.setData({ recommendNoMore: true });
      return;
    }
    this.setData({ recommendLoading: true });
    prepareListImages(slice).then((prepared) => {
      this._recommendPage = page;
      this.setData({
        recommendList: this.data.recommendList.concat(prepared),
        recommendLoading: false,
        recommendNoMore: end >= all.length,
      });
    });
  },

  onReachBottom() {
    this._loadMoreRecommend();
  },

  onShowRecentPopup() {
    const page = 1;
    this._loadRecentPopupPage(page);
    this.setData({ showRecentPopup: true, recentPopupPage: page });
  },

  onCloseRecentPopup() {
    this.setData({ showRecentPopup: false });
  },

  onPreventBubble() {},

  onRecentPopupLoadMore() {
    if (this.data.recentPopupLoading || !this.data.recentPopupHasMore) return;
    const nextPage = this.data.recentPopupPage + 1;
    this._loadRecentPopupPage(nextPage);
  },

  _loadRecentPopupPage(page) {
    this.setData({ recentPopupLoading: true });
    const all = this.data.recent;
    const start = 0;
    const end = page * RECENT_PAGE_SIZE;
    const pageItems = all.slice(start, end);
    this.setData({
      recentPopupList: pageItems,
      recentPopupPage: page,
      recentPopupHasMore: end < all.length,
      recentPopupLoading: false,
    });
  },

  onRecentPopupTap(e) {
    const { platform, itemid } = e.currentTarget.dataset;
    this.setData({ showRecentPopup: false });
    this._gotoAnalysis(platform, itemid);
  },

  onRecommendTap(e) {
    const { platform, itemid } = e.currentTarget.dataset;
    this._gotoAnalysis(platform, itemid);
  },

  _toRelativeImage(url) {
    if (!url) return '';
    const apiIdx = url.indexOf('/api/product/image');
    if (apiIdx >= 0) return url.slice(apiIdx);
    const staticIdx = url.indexOf('/static/products/');
    if (staticIdx >= 0) return url.slice(staticIdx);
    if (/^https?:\/\//i.test(url)) {
      return url.replace(/^https?:\/\/[^/]+/i, '');
    }
    return url;
  },

  onRecentImgError(e) {
    const idx = e.currentTarget.dataset.index;
    if (idx === undefined || idx === null) return;
    this.setData({ [`recent[${idx}]._imgErr`]: true });
  },

  onShow() {
    this._loadRecent();
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
    this.onQuery();
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

  // 统一入口：粘贴链接走解析，普通文本走搜索；两条路最终都汇入差异化推荐
  onQuery() {
    const text = (this.data.linkText || '').trim();
    if (!text) {
      wx.showToast({ title: '粘贴链接或输入机型，如 iPhone 16 Pro', icon: 'none' });
      return;
    }
    if (this._looksLikeLink(text)) {
      this._parseLink(text);
    } else {
      this._search(text);
    }
  },

  _looksLikeLink(text) {
    return /https?:\/\//i.test(text) ||
      /(jd\.com|3\.cn|u\.jd\.com|yangkeduo|pinduoduo|京东|拼多多)/i.test(text);
  },

  _parseLink(linkText) {
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

  _search(keyword) {
    track.event('search_submit');
    api
      .search(keyword)
      .then((list) => prepareListImages(list || []))
      .then((searchResults) => this.setData({ searchResults }))
      .catch((err) => wx.showToast({ title: err.message || '搜索失败', icon: 'none' }));
  },

  onCardTap(e) {
    const item = e.detail;
    if (!item || !item.platform || !item.itemId) return;
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
    // 存相对路径，渲染时再按当前环境 resolveImageUrl；
    // 不能把绝对地址写进 storage——切换 devtools/真机/远程后旧 host 不可达，图就裂了
    const stored = (wx.getStorageSync('recentQueries') || []).filter(
      (r) => !(r.platform === res.platform && r.itemId === res.itemId),
    );
    stored.unshift({
      platform: res.platform,
      itemId: res.itemId,
      title: res.productInfo.title,
      imageUrl: res.productInfo.imageUrl,
    });
    const next = stored.slice(0, RECENT_STORAGE_LIMIT);
    wx.setStorageSync('recentQueries', next);
    prepareListImages(next.map((r) => ({ ...r, _imgErr: false }))).then((recent) => {
      const recentVisible = recent.slice(0, RECENT_VISIBLE_COUNT);
      this.setData({ recent, recentVisible, recentHasMore: recent.length > RECENT_VISIBLE_COUNT });
    });
  },
}, track.pageMixin('search')));
