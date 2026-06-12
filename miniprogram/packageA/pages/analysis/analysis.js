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

const PROVINCES = ['北京市', '上海市', '天津市', '重庆市', '广东省', '江苏省', '浙江省', '四川省', '湖北省', '山东省', '河南省', '福建省', '湖南省', '安徽省', '河北省', '陕西省', '辽宁省', '江西省', '广西壮族自治区', '云南省', '贵州省', '山西省', '吉林省', '黑龙江省', '甘肃省', '内蒙古自治区', '新疆维吾尔自治区', '海南省', '宁夏回族自治区', '青海省', '西藏自治区'];
const GOV_LOCATION_DENY_KEY = 'gov_location_deny_ts';
const GOV_DENY_COOLDOWN = 7 * 24 * 60 * 60 * 1000;

// 省会城市经纬度（用于根据定位匹配最近省份）
const PROVINCE_COORDS = [
  { name: '北京市', lat: 39.9042, lng: 116.4074 },
  { name: '上海市', lat: 31.2304, lng: 121.4737 },
  { name: '天津市', lat: 39.0842, lng: 117.2009 },
  { name: '重庆市', lat: 29.5630, lng: 106.5516 },
  { name: '广东省', lat: 23.1291, lng: 113.2644 },
  { name: '江苏省', lat: 32.0603, lng: 118.7969 },
  { name: '浙江省', lat: 30.2741, lng: 120.1551 },
  { name: '四川省', lat: 30.5728, lng: 104.0668 },
  { name: '湖北省', lat: 30.5928, lng: 114.3055 },
  { name: '山东省', lat: 36.6512, lng: 117.1201 },
  { name: '河南省', lat: 34.7466, lng: 113.6254 },
  { name: '福建省', lat: 26.0745, lng: 119.2965 },
  { name: '湖南省', lat: 28.2282, lng: 112.9388 },
  { name: '安徽省', lat: 31.8612, lng: 117.2830 },
  { name: '河北省', lat: 38.0428, lng: 114.5149 },
  { name: '陕西省', lat: 34.2658, lng: 108.9541 },
  { name: '辽宁省', lat: 41.8057, lng: 123.4315 },
  { name: '江西省', lat: 28.6820, lng: 115.8579 },
  { name: '广西壮族自治区', lat: 22.8170, lng: 108.3665 },
  { name: '云南省', lat: 25.0389, lng: 102.7183 },
  { name: '贵州省', lat: 26.6470, lng: 106.6302 },
  { name: '山西省', lat: 37.8706, lng: 112.5489 },
  { name: '吉林省', lat: 43.8868, lng: 125.3245 },
  { name: '黑龙江省', lat: 45.7500, lng: 126.6500 },
  { name: '甘肃省', lat: 36.0611, lng: 103.8343 },
  { name: '内蒙古自治区', lat: 40.8424, lng: 111.7500 },
  { name: '新疆维吾尔自治区', lat: 43.7930, lng: 87.6271 },
  { name: '海南省', lat: 20.0174, lng: 110.3493 },
  { name: '宁夏回族自治区', lat: 38.4872, lng: 106.2309 },
  { name: '青海省', lat: 36.6171, lng: 101.7782 },
  { name: '西藏自治区', lat: 29.6500, lng: 91.1000 },
];

function nearestProvince(lat, lng) {
  let best = PROVINCE_COORDS[0];
  let minDist = Infinity;
  for (const p of PROVINCE_COORDS) {
    const d = (p.lat - lat) ** 2 + (p.lng - lng) ** 2;
    if (d < minDist) { minDist = d; best = p; }
  }
  return best.name;
}

/** 判断两段文案是否高度重复（用于隐藏与标题雷同的标准型号） */
function isSimilarText(a, b) {
  if (!a || !b) return true;
  const norm = (s) => s.replace(/[\s·\-+/,，、]/g, '').toLowerCase();
  const na = norm(a);
  const nb = norm(b);
  if (na === nb || na.includes(nb) || nb.includes(na)) return true;
  const tokensA = new Set(a.split(/[\s·\-+/,，、]+/).filter((t) => t.length > 1));
  const tokensB = b.split(/[\s·\-+/,，、]+/).filter((t) => t.length > 1);
  if (!tokensB.length) return true;
  const overlap = tokensB.filter((t) => tokensA.has(t)).length;
  return overlap / tokensB.length >= 0.65;
}

/** 过滤与平台/店铺类型重复的活动标签 */
function dedupeProductTags(platformText, shopTypeText, productTags) {
  const redundant = new Set([
    platformText,
    shopTypeText,
    `${platformText}${shopTypeText}`,
    `${platformText}·${shopTypeText}`,
    `${platformText}自营`,
    '京东自营',
    '拼多多官方',
  ].filter(Boolean));
  return productTags.filter((t) => !redundant.has(t.text));
}

function buildProdView(res, platformText, shopTypeText, productTags, shopAlt) {
  const platformBadge = shopTypeText ? `${platformText} · ${shopTypeText}` : platformText;
  const displayTags = dedupeProductTags(platformText, shopTypeText, productTags);
  const title = (res.productInfo && res.productInfo.title) || '';
  const standardName = (res.skuInfo && res.skuInfo.standardName) || '';
  const showStandardName = standardName && !isSimilarText(title, standardName);
  const confidence = (res.skuInfo && res.skuInfo.confidence) || '';
  const needConfirm = !!(res.skuInfo && res.skuInfo.needConfirm);
  let skuWarning = '';
  if (needConfirm) {
    skuWarning = '疑似同款，型号匹配置信度较低，切换店铺前请仔细核对规格';
  } else if (confidence === 'mid') {
    skuWarning = '型号匹配置信度中等，建议核对规格后再购买';
  }
  return {
    platformBadge,
    displayTags,
    showStandardName,
    skuWarning,
    showShopName: !shopAlt && !!(res.productInfo && res.productInfo.shopName),
  };
}

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
    shopAlt: null,
    platformBadge: '',
    displayTags: [],
    showStandardName: false,
    skuWarning: '',
    showShopName: true,
    titleOverflow: false,
    titleModal: { show: false },
    // 国补定位弹窗
    locModal: { show: false },
    regionPicker: { show: false },
    provinces: PROVINCES,
    regionIndex: 0,
  },

  onLoad(query) {
    const { platform, itemId } = query;
    this.setData({ platform, itemId });
    if (query.fromItemId) {
      this._fromItem = {
        itemId: query.fromItemId,
        platform: query.fromPlatform || platform,
        shopType: query.fromShopType || '',
        shopName: decodeURIComponent(query.fromShopName || ''),
        price: query.fromPrice || '',
      };
    }
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
      titleOverflow: false,
      titleModal: { show: false },
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
        if (res.priceInfo && !res.priceInfo.pricePending) {
          res.priceInfo.finalPriceText = yuan(res.priceInfo.estimatedFinalPrice);
          res.priceInfo.displayPriceText = yuan(res.priceInfo.displayPrice);
        } else if (res.priceInfo) {
          res.priceInfo.finalPriceText = null;
          res.priceInfo.displayPriceText = null;
        }
        const productTags = (res.productInfo.activityTags || []).map((t) => ({
          text: t,
          gov: /国补/.test(t),
        }));
        const alt = res.shopAlternative;
        let shopAlt = null;
        if (alt) {
          shopAlt = {
            itemId: alt.itemId,
            platform: alt.platform,
            shopType: alt.shopType,
            shopTypeText: shopTypeName(alt.shopType),
            shopName: alt.shopName,
            price: yuan(alt.estimatedFinalPrice),
          };
        } else if (this._fromItem) {
          const fi = this._fromItem;
          shopAlt = {
            itemId: fi.itemId,
            platform: fi.platform,
            shopType: fi.shopType,
            shopTypeText: shopTypeName(fi.shopType),
            shopName: fi.shopName,
            price: fi.price,
          };
        }
        const platformText = platformName(res.productInfo.platform);
        const shopTypeText = shopTypeName(res.productInfo.shopType);
        const prodView = buildProdView(res, platformText, shopTypeText, productTags, shopAlt);
        this.setData({
          loading: false,
          data: res,
          platformText,
          shopTypeText,
          productTags,
          crossView,
          sameItemBuyable,
          shopAlt,
          ...prodView,
        });
        track.event('analysis_view', {
          platform: this.data.platform,
          is_price_compare: !sameItemBuyable,
        });
        this._checkGovLocationPopup(productTags);
        this._checkTitleOverflow();
      }))
      .catch((err) => {
        this.setData({ loading: false, error: err.message || '加载失败' });
      });
  },

  _checkTitleOverflow() {
    wx.nextTick(() => {
      const q = wx.createSelectorQuery().in(this);
      q.select('.prod-title-measure').boundingClientRect();
      q.select('.prod-title').boundingClientRect();
      q.exec((res) => {
        const full = res[0];
        const clamped = res[1];
        const overflow = !!(full && clamped && full.height > clamped.height + 1);
        if (overflow !== this.data.titleOverflow) {
          this.setData({ titleOverflow: overflow });
        }
      });
    });
  },

  onTitleTap() {
    if (!this.data.titleOverflow) return;
    this.setData({ 'titleModal.show': true });
  },

  onCloseTitleModal() {
    this.setData({ 'titleModal.show': false });
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

  onSwitchShop() {
    const alt = this.data.shopAlt;
    if (!alt) return;
    const cur = this.data.data;
    const curPrice = cur.priceInfo && !cur.priceInfo.pricePending
      ? cur.priceInfo.finalPriceText : '';
    const params = [
      `platform=${alt.platform}`,
      `itemId=${alt.itemId}`,
      `fromItemId=${this.data.itemId}`,
      `fromPlatform=${this.data.platform}`,
      `fromShopType=${cur.productInfo.shopType || ''}`,
      `fromShopName=${encodeURIComponent(cur.productInfo.shopName || '')}`,
      `fromPrice=${curPrice}`,
    ].join('&');
    wx.redirectTo({
      url: `/packageA/pages/analysis/analysis?${params}`,
    });
  },

  // ---- 国补定位弹窗 ----

  _checkGovLocationPopup(productTags) {
    const hasGov = productTags.some((t) => t.gov);
    if (!hasGov) return;
    const assets = app.getAssets();
    if (assets.govSubsidyRegion) return;
    const denyTs = wx.getStorageSync(GOV_LOCATION_DENY_KEY) || 0;
    if (Date.now() - denyTs < GOV_DENY_COOLDOWN) return;
    this.setData({ 'locModal.show': true });
  },

  onLocAllow() {
    this.setData({ 'locModal.show': false });
    wx.getFuzzyLocation({
      type: 'wgs84',
      success: (res) => {
        this._resolveProvince(res.latitude, res.longitude);
      },
      fail: () => {
        wx.showToast({ title: '获取位置失败，请手动选择', icon: 'none' });
        this.setData({ 'regionPicker.show': true });
      },
    });
  },

  onLocDeny() {
    this.setData({ 'locModal.show': false });
    wx.setStorageSync(GOV_LOCATION_DENY_KEY, Date.now());
  },

  onLocManual() {
    this.setData({ 'locModal.show': false, 'regionPicker.show': true });
  },

  onRegionChange(e) {
    const idx = e.detail.value[0];
    this.setData({ regionIndex: idx });
  },

  onRegionConfirm() {
    const province = PROVINCES[this.data.regionIndex];
    this.setData({ 'regionPicker.show': false });
    this._saveRegionAndReload(province);
  },

  onCloseRegionPicker() {
    this.setData({ 'regionPicker.show': false });
  },

  _resolveProvince(lat, lng) {
    const province = nearestProvince(lat, lng);
    this._saveRegionAndReload(province);
  },

  _saveRegionAndReload(province) {
    app.setAssets({ govSubsidyRegion: province });
    wx.showToast({ title: `已设置国补地区：${province}`, icon: 'none' });
    this._load();
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
