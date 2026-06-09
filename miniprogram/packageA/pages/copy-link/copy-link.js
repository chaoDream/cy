const { copyPurchaseLink } = require('../../../utils/purchase');

Page({
  data: {
    link: '',
    reason: '',
    linkType: '',
  },

  onLoad(query) {
    this.setData({
      link: decodeURIComponent(query.url || ''),
      reason: decodeURIComponent(query.reason || ''),
      linkType: decodeURIComponent(query.linkType || ''),
    });
  },

  onCopyTap() {
    const { link, linkType } = this.data;
    if (!link) {
      wx.showToast({ title: '链接为空', icon: 'none' });
      return;
    }
    copyPurchaseLink(link, linkType || 'default');
  },
});
