const { request } = require('./request');

function event(name, props = {}) {
  request({
    url: '/api/track/event',
    method: 'POST',
    data: { event: name, props },
    auth: true,
  }).catch(() => {});
}

/**
 * 页面级自动埋点 mixin：page_view（进入）+ page_leave（离开，带停留时长）。
 * 用法：在每个 Page({}) 的选项里展开 ...track.pageMixin('pageName')
 * 会注入 onShow / onHide / onUnload，与页面自身的同名钩子安全合并。
 */
function pageMixin(pageName) {
  return {
    _trackShowTime: 0,

    onShow() {
      this._trackShowTime = Date.now();
      event('page_view', { page: pageName });
    },

    onHide() {
      this._reportLeave(pageName);
    },

    onUnload() {
      this._reportLeave(pageName);
    },

    _reportLeave(page) {
      if (!this._trackShowTime) return;
      const duration = Math.round((Date.now() - this._trackShowTime) / 1000);
      this._trackShowTime = 0;
      event('page_leave', { page, duration });
    },
  };
}

/**
 * 合并两个 Page 选项对象，生命周期钩子串联执行，其余属性后者覆盖。
 */
function mergePage(base, mixin) {
  const hooks = ['onLoad', 'onShow', 'onHide', 'onUnload', 'onReady',
    'onPullDownRefresh', 'onReachBottom', 'onShareAppMessage'];
  const merged = Object.assign({}, mixin, base);
  hooks.forEach(h => {
    if (typeof base[h] === 'function' && typeof mixin[h] === 'function') {
      merged[h] = function (...args) {
        mixin[h].apply(this, args);
        base[h].apply(this, args);
      };
    }
  });
  return merged;
}

module.exports = { event, pageMixin, mergePage };
