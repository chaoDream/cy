const { subscribeTemplateId } = require('../../../utils/config');

Page({
  data: {
    defaultRule: 'auto', // auto | low30 | custom
  },

  onLoad() {
    const rule = wx.getStorageSync('defaultTargetRule') || 'auto';
    this.setData({ defaultRule: rule });
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
