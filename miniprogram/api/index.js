const { request } = require('../utils/request');

module.exports = {
  // 链接解析（PRD §11.1）
  parseLink: (linkText) =>
    request({ url: '/api/link/parse', method: 'POST', data: { linkText }, showLoading: true }),

  // 商品分析（PRD §11.2）
  analysis: (platform, itemId, assets) =>
    request({
      url: `/api/product/analysis?platform=${platform}&item_id=${itemId}&assets=${encodeURIComponent(
        JSON.stringify(assets || {}),
      )}`,
      method: 'GET',
      auth: false,
    }),

  // 搜索
  search: (keyword) =>
    request({ url: `/api/product/search?keyword=${encodeURIComponent(keyword)}`, auth: false }),

  // 盯价（PRD §11.3）
  createWatch: (data) => request({ url: '/api/watch/create', method: 'POST', data }),
  watchList: () => request({ url: '/api/watch/list' }),
  updateTarget: (watchId, targetPrice) =>
    request({ url: '/api/watch/target', method: 'POST', data: { watchId, targetPrice } }),
  toggleNotify: (watchId, enabled) =>
    request({ url: '/api/watch/notify', method: 'POST', data: { watchId, enabled } }),
  removeWatch: (watchId) => request({ url: '/api/watch/remove', method: 'POST', data: { watchId } }),

  // 低价榜（PRD §11.4）
  rank: (params = {}) => {
    const q = Object.entries(params)
      .filter(([, v]) => v !== '' && v !== undefined && v !== null)
      .map(([k, v]) => `${k}=${encodeURIComponent(v)}`)
      .join('&');
    return request({ url: `/api/rank/phone${q ? `?${q}` : ''}`, auth: false });
  },

  // 用户资产库
  getProfile: () => request({ url: '/api/user/profile' }),
  saveAssets: (assets) => request({ url: '/api/user/assets', method: 'POST', data: assets }),

  // 反馈（PRD §11.5）
  feedback: (data) => request({ url: '/api/feedback/create', method: 'POST', data }),
};
