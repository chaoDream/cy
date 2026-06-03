const { subscribeTemplateId, getApiTarget, setApiTarget, getBaseUrl, getApiTargetLabel } = require('../../../utils/config');
const { setToken } = require('../../../utils/request');
const { clearUserInfo } = require('../../../utils/auth');

Page({
  data: {
    defaultRule: 'auto', // auto | low30 | custom
    apiTarget: 'local',
    apiTargetLabel: '',
    baseUrl: '',
  },

  onLoad() {
    const rule = wx.getStorageSync('defaultTargetRule') || 'auto';
    this.refreshApiTarget();
    this.setData({ defaultRule: rule });
  },

  onShow() {
    this.refreshApiTarget();
  },

  refreshApiTarget() {
    this.setData({
      apiTarget: getApiTarget(),
      apiTargetLabel: getApiTargetLabel(),
      baseUrl: getBaseUrl(),
    });
  },

  onApiTargetChange(e) {
    const target = e.currentTarget.dataset.target;
    if (target === this.data.apiTarget) return;
    setApiTarget(target);
    setToken('');
    clearUserInfo();
    this.refreshApiTarget();
    wx.showToast({
      title: target === 'remote' ? '已切换远程' : '已切换本地',
      icon: 'none',
    });
  },

  onRuleChange(e) {
    const rule = e.currentTarget.dataset.rule;
    this.setData({ defaultRule: rule });
    wx.setStorageSync('defaultTargetRule', rule);
  },

  // 引导用户重新授权订阅消息（一次订阅一次下发，命中后需复订）
  onResubscribe() {
    wx.requestSubscribeMessage({
      tmplIds: [subscribeTemplateId],
      success: () => wx.showToast({ title: '已开启降价提醒', icon: 'none' }),
      fail: () => wx.showToast({ title: '授权未完成', icon: 'none' }),
    });
  },
});
