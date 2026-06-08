/**
 * 复制购买链接。
 *
 * 隐私：采用微信官方 onNeedPrivacyAuthorization 流程（见 components/privacy-popup）。
 * 直接调用 wx.setClipboardData：未授权时微信会触发全局隐私弹窗，用户在弹窗内点
 * 「同意并继续」后，本次 setClipboardData 会自动继续执行并写入剪贴板，无需跳页或重试。
 */

const PRIVACY_OK_KEY = '__clipboard_privacy_ok__';

const GUIDE = {
  cps: '联盟推广链接已复制。请打开京东/拼多多 App，在顶部搜索框粘贴并访问。',
  product_page: '商品页链接已复制。请打开京东 App，在搜索框粘贴链接后访问。',
  share_url: '分享链接已复制。请打开京东 App，在搜索框粘贴链接后访问。',
  default: '链接已复制，请打开对应购物 App 粘贴访问。',
};

function markPrivacyAgreed() {
  try {
    wx.setStorageSync(PRIVACY_OK_KEY, '1');
  } catch (e) {
    // ignore
  }
  const app = getApp();
  if (app && app.globalData) {
    app.globalData.clipboardPrivacyOk = true;
  }
}

function showCopySuccessGuide(linkType) {
  wx.showToast({ title: '复制成功', icon: 'success', duration: 2000 });
  setTimeout(() => {
    wx.showModal({
      title: '去购买',
      content: GUIDE[linkType] || GUIDE.default,
      showCancel: false,
      confirmText: '知道了',
    });
  }, 400);
}

function openCopyLinkPage(link, reason, linkType) {
  const q = [
    `url=${encodeURIComponent(link)}`,
    `reason=${encodeURIComponent(reason || '')}`,
    `linkType=${encodeURIComponent(linkType || '')}`,
  ].join('&');
  wx.navigateTo({ url: `/packageA/pages/copy-link/copy-link?${q}` });
}

function parseCopyError(err) {
  const msg = ((err && err.errMsg) || '').toLowerCase();
  if (/privacy|authorize|authorization/.test(msg)) {
    return { type: 'privacy', text: '需先同意隐私协议' };
  }
  if (/permission|denied/.test(msg)) {
    return { type: 'permission', text: '剪贴板写入被拒绝' };
  }
  return { type: 'other', text: (err && err.errMsg) || '复制失败' };
}

/**
 * 复制购买链接。隐私授权由全局 privacy-popup 组件自动处理。
 * @param {string} link
 * @param {string} [linkType]
 */
function copyPurchaseLink(link, linkType) {
  if (!link) {
    wx.showToast({ title: '暂无购买链接', icon: 'none' });
    return;
  }
  const type = linkType || 'default';
  wx.setClipboardData({
    data: link,
    success: () => {
      markPrivacyAgreed();
      showCopySuccessGuide(type);
    },
    fail: (err) => {
      const parsed = parseCopyError(err);
      // 用户在隐私弹窗点了「拒绝」，或其它失败：给出查看链接的兜底
      wx.showModal({
        title: '未能复制',
        content: parsed.type === 'privacy' ? '需同意隐私协议后才能复制购买链接。' : parsed.text,
        confirmText: '查看链接',
        cancelText: '取消',
        success: (res) => {
          if (res.confirm) openCopyLinkPage(link, parsed.text, type);
        },
      });
    },
  });
}

function copyLinkDirect(link) {
  return new Promise((resolve, reject) => {
    if (!link) {
      reject(new Error('empty'));
      return;
    }
    wx.setClipboardData({
      data: link,
      success: () => {
        markPrivacyAgreed();
        wx.showToast({ title: '复制成功', icon: 'success', duration: 2000 });
        resolve();
      },
      fail: reject,
    });
  });
}

module.exports = {
  copyPurchaseLink,
  copyLinkDirect,
  markPrivacyAgreed,
  openCopyLinkPage,
  parseCopyError,
};
