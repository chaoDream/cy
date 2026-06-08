const { copyLinkDirect, parseCopyError } = require('../../../utils/purchase');

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
    const { link } = this.data;
    if (!link) {
      wx.showToast({ title: '链接为空', icon: 'none' });
      return;
    }
    copyLinkDirect(link).catch((err) => {
      const parsed = parseCopyError(err);
      wx.showToast({ title: parsed.text || '复制失败', icon: 'none' });
    });
  },
});
