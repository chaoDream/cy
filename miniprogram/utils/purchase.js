/**
 * 复制购买链接 + 隐私授权编排。
 *
 * 流程：
 * 1. 已授权（本地标记或 getPrivacySetting=已同意）→ 直接写剪贴板。
 * 2. 未授权 → 主动弹出自定义隐私弹窗（privacy-popup 组件）：
 *    - 同意 → agreePrivacyAuthorization 授权后写剪贴板。
 *    - 不同意 → 跳转复制购买链接页面。
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

/** 真正写剪贴板（调用方需已确保隐私已授权） */
function writeClipboard(link, linkType) {
  const type = linkType || 'default';
  wx.setClipboardData({
    data: link,
    success: () => {
      markPrivacyAgreed();
      showCopySuccessGuide(type);
    },
    fail: (err) => {
      const parsed = parseCopyError(err);
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

function showPrivacyPopup(link, linkType) {
  const app = getApp();
  const popup = app && app.globalData && app.globalData.privacyPopup;
  if (popup && typeof popup.show === 'function') {
    popup.show(link, linkType);
  } else {
    // 兜底：没有挂载弹窗组件时直接进复制链接页
    openCopyLinkPage(link, '', linkType);
  }
}

/**
 * 复制购买链接，自动处理隐私授权。
 * @param {string} link
 * @param {string} [linkType]
 */
function copyPurchaseLink(link, linkType) {
  if (!link) {
    wx.showToast({ title: '暂无购买链接', icon: 'none' });
    return;
  }
  const type = linkType || 'default';
  const app = getApp();
  if (app && app.globalData) {
    app.globalData.pendingPurchase = { link, linkType: type };
  }
  // 权威判断只信 getPrivacySetting，不信本地标记（本地标记可能与微信端实际授权状态不一致）
  if (typeof wx.getPrivacySetting === 'function') {
    wx.getPrivacySetting({
      success: (res) => {
        if (res.needAuthorization) {
          showPrivacyPopup(link, type);
        } else {
          markPrivacyAgreed();
          writeClipboard(link, type);
        }
      },
      // 取不到隐私状态：保守起见也弹授权弹窗，避免直接失败
      fail: () => showPrivacyPopup(link, type),
    });
  } else {
    writeClipboard(link, type);
  }
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
  writeClipboard,
  markPrivacyAgreed,
  openCopyLinkPage,
  parseCopyError,
};
