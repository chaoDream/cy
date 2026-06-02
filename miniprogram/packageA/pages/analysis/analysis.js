const api = require('../../../api/index');
const track = require('../../../utils/track');
const { ensureLogin } = require('../../../utils/auth');
const { subscribeTemplateId } = require('../../../utils/config');
const { platformName, shopTypeName, yuan, resolveImageUrl } = require('../../../utils/format');

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
  },

  onLoad(query) {
    const { platform, itemId } = query;
    this.setData({ platform, itemId });
    this._load();
  },

  _load() {
    this.setData({ loading: true, error: '' });
    api
      .analysis(this.data.platform, this.data.itemId, app.getAssets())
      .then((res) => {
        if (res.productInfo) {
          res.productInfo.imageUrl = resolveImageUrl(res.productInfo.imageUrl);
        }
        const crossView = (res.crossPlatform || []).map((c) => ({
          ...c,
          platformText: platformName(c.platform),
          shopTypeText: shopTypeName(c.shopType),
          price: yuan(c.estimatedFinalPrice),
        }));
        this.setData({
          loading: false,
          data: res,
          platformText: platformName(res.productInfo.platform),
          shopTypeText: shopTypeName(res.productInfo.shopType),
          crossView,
        });
        track.event('analysis_view', { platform: this.data.platform });
      })
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

  // 盯价：需登录 + 申请订阅消息授权（PRD §5.5）
  onWatch() {
    ensureLogin()
      .then(() => this._requestSubscribe())
      .then(() => {
        const d = this.data.data;
        return api.createWatch({
          rawProductId: d.productInfo.rawProductId || d.rawProductId,
          targetPrice: null, // 由后端默认目标价规则计算
        });
      })
      .then((res) => {
        track.event('watch_create');
        wx.showToast({ title: `已盯价，目标价 ¥${res.targetPrice}`, icon: 'none' });
      })
      .catch((err) => {
        if (err && err.needLogin) {
          wx.showToast({ title: '请先登录', icon: 'none' });
        } else {
          wx.showToast({ title: (err && err.message) || '盯价失败', icon: 'none' });
        }
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

  // 去购买：走 CPS 转链（透明披露）
  onBuy() {
    const link = this.data.data.cpsLink;
    track.event('purchase_click', { platform: this.data.platform, is_cps_matched: !!link });
    if (!link) {
      wx.showToast({ title: '暂无购买链接', icon: 'none' });
      return;
    }
    wx.setClipboardData({
      data: link,
      success: () => wx.showModal({
        title: '去购买',
        content: '购买链接已复制，请到对应 App 打开。本产品通过官方联盟获取返佣，不影响推荐排序。',
        showCancel: false,
      }),
    });
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
