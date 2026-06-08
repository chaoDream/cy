const { markPrivacyAgreed } = require('../../../utils/purchase');

Page({
  data: {
    fromPurchase: false,
  },

  onLoad(query) {
    this.setData({ fromPurchase: query.from === 'purchase' });
  },

  openOfficialPrivacy() {
    if (wx.openPrivacyContract) {
      wx.openPrivacyContract({
        fail: () => wx.showToast({ title: '请在小程序后台配置隐私协议', icon: 'none' }),
      });
    } else {
      wx.showToast({ title: '当前微信版本不支持', icon: 'none' });
    }
  },

  onAgreePrivacy() {
    markPrivacyAgreed();
    wx.showToast({ title: '已同意隐私协议', icon: 'success' });
    if (this.data.fromPurchase) {
      setTimeout(() => wx.navigateBack(), 600);
    }
  },
});
