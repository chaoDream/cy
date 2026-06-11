const api = require('../../../api/index');
const track = require('../../../utils/track');
const { ensureLogin, getUserId } = require('../../../utils/auth');
const { subscribeTemplateId } = require('../../../utils/config');
const { platformName, shopTypeName, yuan, yuanTrim } = require('../../../utils/format');

/** 价格输入清洗：仅留数字与一个小数点，最多两位小数 */
function sanitizeMoney(v) {
  let s = String(v).replace(/[^\d.]/g, '');
  const dot = s.indexOf('.');
  if (dot >= 0) {
    s = s.slice(0, dot + 1) + s.slice(dot + 1).replace(/\./g, '').slice(0, 2);
  }
  return s;
}
const { prepareImageForDisplay } = require('../../../utils/image');
const { copyPurchaseLink } = require('../../../utils/purchase');

const app = getApp();

Page({
  data: {
    loading: true,
    error: '',
    platform: '',
    itemId: '',
    data: null,
    platformText: '',
    shopTypeText: '',
    productTags: [],
    crossView: [],
    sameItemBuyable: true,
    aiLoading: true,
    aiRecommendation: null,
    aiError: '',
    watchModal: { show: false },
  },

  onLoad(query) {
    const { platform, itemId } = query;
    this.setData({ platform, itemId });
    this._load();
  },

  _load() {
    const { platform, itemId } = this.data;
    const assets = app.getAssets();
    const uid = getUserId();
    this.setData({
      loading: true,
      error: '',
      aiLoading: true,
      aiRecommendation: null,
      aiError: '',
    });
    this._loadAi(platform, itemId, assets);
    api
      .analysis(platform, itemId, assets, uid)
      .then((res) => prepareImageForDisplay(res.productInfo && res.productInfo.imageUrl).then((img) => {
        if (res.productInfo) {
          res.productInfo.imageUrl = img;
        }
        const crossView = (res.crossPlatform || []).map((c) => ({
          ...c,
          platformText: platformName(c.platform),
          shopTypeText: shopTypeName(c.shopType),
          price: yuan(c.estimatedFinalPrice),
        }));
        const sameItemBuyable = !!res.cpsLink;
        const finalPrice = yuan(res.priceInfo && res.priceInfo.estimatedFinalPrice);
        const displayPrice = yuan(res.priceInfo && res.priceInfo.displayPrice);
        const pricePending = finalPrice === '--' || Number(finalPrice) <= 0;
        if (res.priceInfo) {
          res.priceInfo.finalPriceText = finalPrice;
          res.priceInfo.displayPriceText = displayPrice;
          res.priceInfo.pricePending = pricePending;
        }
        const productTags = (res.productInfo.activityTags || []).map((t) => ({
          text: t,
          gov: /国补/.test(t),
        }));
        this.setData({
          loading: false,
          data: res,
          platformText: platformName(res.productInfo.platform),
          shopTypeText: shopTypeName(res.productInfo.shopType),
          productTags,
          crossView,
          sameItemBuyable,
        });
        track.event('analysis_view', {
          platform: this.data.platform,
          is_price_compare: !sameItemBuyable,
        });
      }))
      .catch((err) => {
        this.setData({ loading: false, error: err.message || '加载失败' });
      });
  },

  _loadAi(platform, itemId, assets, forceRule = false) {
    api
      .aiRecommendation(platform, itemId, assets, forceRule)
      .then((rec) => {
        this.setData({ aiLoading: false, aiRecommendation: rec, aiError: '' });
      })
      .catch(() => {
        if (!forceRule) {
          this._loadAi(platform, itemId, assets, true);
          return;
        }
        this.setData({ aiLoading: false, aiError: '购买建议暂时无法获取' });
      });
  },

  onRetryAi() {
    const { platform, itemId } = this.data;
    this.setData({ aiLoading: true, aiError: '' });
    this._loadAi(platform, itemId, app.getAssets(), false);
  },

  onPriceExpand() {
    track.event('price_detail_expand');
  },

  onRiskView() {
    track.event('risk_detail_view');
  },

  // 盯价：需登录后弹出盯价弹窗（PRD §5.5）
  onWatch() {
    ensureLogin()
      .then(() => {
        const info = this.data.data && this.data.data.priceInfo;
        const current = Number(info && info.estimatedFinalPrice);
        if (!current || current <= 0) {
          wx.showToast({ title: '当前价格获取中，请稍后再试', icon: 'none' });
          return;
        }
        this.setData({ watchModal: this._buildWatchModal(current) });
      })
      .catch((err) => {
        if (err && err.needLogin) wx.showToast({ title: '请先登录', icon: 'none' });
      });
  },

  // 构造弹窗初始态：默认选中「降价5%」，输入框无焦点
  _buildWatchModal(current) {
    const mk = (pct) => {
      const price = Math.round(current * (1 - pct / 100) * 100) / 100;
      return { pct, price, priceText: yuanTrim(price) };
    };
    return {
      show: true,
      currentPrice: current,
      currentPriceText: yuanTrim(current),
      options: [mk(5), mk(10), mk(20)],
      selectedPct: 5,
      customPrice: '',
      inputFocus: false,
      inputError: false,
      scope: 'merchant',
      canSubmit: true,
    };
  },

  // 点击降价档位：选中它、清空输入框、收起键盘
  onPickPct(e) {
    const pct = Number(e.currentTarget.dataset.pct);
    this.setData({
      'watchModal.selectedPct': pct,
      'watchModal.customPrice': '',
      'watchModal.inputFocus': false,
      'watchModal.inputError': false,
      'watchModal.canSubmit': true,
    });
  },

  // 输入框获取焦点：取消所有档位选中
  onCustomFocus() {
    this.setData({
      'watchModal.selectedPct': null,
      'watchModal.inputFocus': true,
    });
  },

  // 输入价格：限制两位小数，超过当前价则报错并禁用提交
  onCustomInput(e) {
    const val = sanitizeMoney(e.detail.value);
    const num = Number(val);
    const current = this.data.watchModal.currentPrice;
    const over = val !== '' && num > current;
    const valid = val !== '' && num > 0 && num <= current;
    this.setData({
      'watchModal.selectedPct': null,
      'watchModal.customPrice': val,
      'watchModal.inputError': over,
      'watchModal.canSubmit': valid,
    });
    return val; // 受控输入，强制清洗后的值
  },

  onPickScope(e) {
    this.setData({ 'watchModal.scope': e.currentTarget.dataset.scope });
  },

  onCloseWatchModal() {
    this.setData({ 'watchModal.show': false });
  },

  noop() {},

  // 提交盯价：目标价必须 > 0 且不超过当前价
  onConfirmWatch() {
    const wm = this.data.watchModal;
    if (!wm.canSubmit) return;
    const target = wm.selectedPct != null
      ? wm.options.find((o) => o.pct === wm.selectedPct).price
      : Number(wm.customPrice);
    if (!(target > 0) || target > wm.currentPrice) return;
    const scope = wm.scope;
    this.setData({ 'watchModal.show': false });
    this._requestSubscribe()
      .then(() => {
        const d = this.data.data;
        return api.createWatch({
          rawProductId: d.productInfo.rawProductId || d.rawProductId,
          targetPrice: target,
          watchMode: scope,
        });
      })
      .then((res) => {
        track.event('watch_create');
        wx.showToast({ title: `已盯价，目标价 ¥${yuanTrim(res.targetPrice)}`, icon: 'none' });
      })
      .catch((err) => {
        wx.showToast({ title: (err && err.message) || '盯价失败', icon: 'none' });
      });
  },

  _requestSubscribe() {
    return new Promise((resolve) => {
      wx.requestSubscribeMessage({
        tmplIds: [subscribeTemplateId],
        success: () => resolve(),
        fail: () => resolve(), // 用户拒绝也允许盯价，仅不下发
      });
    });
  },

  onBuy() {
    this._executeBuy();
  },

  _executeBuy() {
    const d = this.data.data;
    if (!d) {
      wx.showToast({ title: '页面加载中，请稍候', icon: 'none' });
      return;
    }
    const link = d.cpsLink;
    const linkType = (d.productInfo && d.productInfo.purchaseLinkType) || 'cps';
    if (link) {
      track.event('purchase_click', {
        platform: this.data.platform,
        is_cps_matched: true,
        is_price_compare: false,
        source: 'same_item',
      });
      this._copyAndGuide(link, linkType, this.data.platform);
      return;
    }
    // 同款比价无返利：引导走差异化/跨平台更优选择
    track.event('purchase_click', {
      platform: this.data.platform,
      is_cps_matched: false,
      is_price_compare: true,
      source: 'same_item_blocked',
    });
    const best = (this.data.crossView || []).find((c) => c.cpsLink);
    if (best) {
      wx.showModal({
        title: '为你找到更优选择',
        content: `这款直接购买可能无返利，推荐 ${best.platformText} 同款 ¥${best.price}，是否前往？`,
        confirmText: '看看',
        success: (r) => {
          if (r.confirm) this._copyAndGuide(best.cpsLink, 'cps', best.platform);
        },
      });
    } else {
      wx.showToast({ title: '建议在下方更优选择中挑选', icon: 'none' });
    }
  },

  // 跨平台/差异化推荐项直接购买
  onBuyCross(e) {
    const idx = e.currentTarget.dataset.idx;
    const item = (this.data.crossView || [])[idx];
    if (!item || !item.cpsLink) {
      wx.showToast({ title: '该商品暂无购买链接', icon: 'none' });
      return;
    }
    track.event('purchase_click', {
      platform: item.platform,
      is_cps_matched: true,
      source: 'cross_platform',
    });
    this._copyAndGuide(item.cpsLink, 'cps', item.platform);
  },

  _copyAndGuide(link, linkType, platform) {
    copyPurchaseLink(link, linkType, platform);
  },

  onFeedback() {
    wx.navigateTo({ url: `/packageA/pages/data-notice/data-notice?mode=feedback&raw=${this.data.data.productInfo.rawProductId || ''}` });
  },

  onShareAppMessage() {
    track.event('share_card_click');
    const d = this.data.data;
    return {
      title: `${d.productInfo.title} 真实到手价 ¥${yuan(d.priceInfo.estimatedFinalPrice)}`,
      path: `/packageA/pages/analysis/analysis?platform=${this.data.platform}&itemId=${this.data.itemId}`,
    };
  },
});
