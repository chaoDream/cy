const { copyPurchaseLink, buildPurchaseGuide } = require('../../../utils/purchase');
const track = require('../../../utils/track');

Page(track.mergePage({
  data: {
    link: '',
    reason: '',
    linkType: '',
    platform: '',
    tip: '',
  },

  onLoad(query) {
    const link = decodeURIComponent(query.url || '');
    const linkType = decodeURIComponent(query.linkType || '');
    const platform = decodeURIComponent(query.platform || '');
    this.setData({
      link,
      reason: decodeURIComponent(query.reason || ''),
      linkType,
      platform,
      tip: buildPurchaseGuide(link, linkType),
    });
  },

  onCopyTap() {
    const { link, linkType, platform } = this.data;
    if (!link) {
      wx.showToast({ title: '链接为空', icon: 'none' });
      return;
    }
    copyPurchaseLink(link, linkType || 'default', platform);
  },
}, track.pageMixin('copy-link')));
