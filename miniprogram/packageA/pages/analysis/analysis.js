const api = require('../../../api/index');
const track = require('../../../utils/track');
const { ensureLogin, getUserId } = require('../../../utils/auth');
const { subscribeTemplateId } = require('../../../utils/config');
const { platformName, shopTypeName, yuan } = require('../../../utils/format');
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
    crossView: [],
    // 同款是否可直接购买（拼多多比价预判命中时为 false，引导看更优选择）
    sameItemBuyable: true,
  },

  onLoad(query) {
    const { platform, itemId } = query;
    this.setData({ platform, itemId });
    this._load();
  },

  _load() {
    this.setData({ loading: true, error: '' });
    api
      .analysis(this.data.platform, this.data.itemId, app.getAssets(), getUserId())
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
        this.setData({
          loading: false,
          data: res,
          platformText: platformName(res.productInfo.platform),
          shopTypeText: shopTypeName(res.productInfo.shopType),
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

  onPriceExpand() {
    track.event('price_detail_expand');
  },

  onRiskView() {
    track.event('risk_detail_view');
  },

  // 盯价：需登录 + 选择盯价范围 + 申请订阅消息授权（PRD §5.5）
  onWatch() {
    ensureLogin()
      .then(() => this._chooseWatchMode())
      .then((watchMode) => this._requestSubscribe().then(() => watchMode))
      .then((watchMode) => {
        const d = this.data.data;
        return api.createWatch({
          rawProductId: d.productInfo.rawProductId || d.rawProductId,
          targetPrice: null, // 由后端默认目标价规则计算
          watchMode,
        });
      })
      .then((res) => {
        track.event('watch_create');
        const scope = res.watchMode === 'platform_lowest' ? '全平台同款最低价' : '当前商家';
        wx.showToast({ title: `已盯${scope}，目标价 ¥${res.targetPrice}`, icon: 'none' });
      })
      .catch((err) => {
        if (err && err.cancelled) return; // 用户取消选择，不提示
        if (err && err.needLogin) {
          wx.showToast({ title: '请先登录', icon: 'none' });
        } else {
          wx.showToast({ title: (err && err.message) || '盯价失败', icon: 'none' });
        }
      });
  },

  // 选择盯价范围：默认只盯当前商家这条链接
  _chooseWatchMode() {
    return new Promise((resolve, reject) => {
      wx.showActionSheet({
        itemList: ['只盯当前商家这个价', '盯全平台同款最低价'],
        success: (res) => resolve(res.tapIndex === 1 ? 'platform_lowest' : 'merchant'),
        fail: () => reject({ cancelled: true }),
      });
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
      this._copyAndGuide(link, linkType);
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
          if (r.confirm) this._copyAndGuide(best.cpsLink, 'cps');
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
    this._copyAndGuide(item.cpsLink, 'cps');
  },

  _copyAndGuide(link, linkType) {
    copyPurchaseLink(link, linkType);
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
