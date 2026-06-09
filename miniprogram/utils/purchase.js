/**
 * 复制购买链接 + 隐私授权。
 *
 * 流程（微信官方推荐）：
 * 1. 用户点击购买 → wx.requirePrivacyAuthorize
 * 2. 未授权 → onNeedPrivacyAuthorization → privacy-popup → resolve
 * 3. requirePrivacyAuthorize success → wx.setClipboardData
 */

const dbg = require('./privacy-debug');

function buildPurchaseGuide(link, linkType) {
  if (linkType === 'product_page' || /item\.jd\.com/i.test(link || '')) {
    return '链接已复制。用手机浏览器打开，或发到微信文件传输助手后点击，即可进入商品详情页。';
  }
  return '链接已复制。用手机浏览器打开，或发到微信后点击，即可进入商品页。';
}

function markPrivacyAgreed() {
  const app = getApp();
  if (app && app.globalData) {
    app.globalData.clipboardPrivacyOk = true;
  }
}

function showCopySuccessGuide(link, linkType, platform) {
  wx.showToast({ title: '复制成功', icon: 'success', duration: 2000 });
  setTimeout(() => {
    wx.showModal({
      title: '去购买',
      content: buildPurchaseGuide(link, linkType),
      showCancel: false,
      confirmText: '知道了',
    });
  }, 400);
}

function openCopyLinkPage(link, reason, linkType, platform) {
  const q = [
    `url=${encodeURIComponent(link)}`,
    `reason=${encodeURIComponent(reason || '')}`,
    `linkType=${encodeURIComponent(linkType || '')}`,
    `platform=${encodeURIComponent(platform || '')}`,
  ].join('&');
  wx.navigateTo({ url: `/packageA/pages/copy-link/copy-link?${q}` });
}

function parseCopyError(err) {
  const raw = dbg.formatErr(err);
  const msg = raw.toLowerCase();
  // 小程序后台「用户隐私保护指引」未声明剪贴板 API——代码无法绕过，只能后台补配置
  if (/not declared|api scope|未在隐私协议中声明|未声明/.test(msg)) {
    return {
      type: 'not_declared',
      text: '小程序后台未声明剪贴板权限，暂无法自动复制',
      raw,
    };
  }
  if (/privacy|authorize|authorization/.test(msg)) {
    return { type: 'privacy', text: '需先同意隐私协议', raw };
  }
  if (/permission|denied/.test(msg)) {
    return { type: 'permission', text: '剪贴板写入被拒绝', raw };
  }
  return { type: 'other', text: raw || '复制失败', raw };
}

function handleCopyFail(link, linkType, platform, parsed, context) {
  dbg.push('copyFail', { context, type: parsed.type, raw: parsed.raw });
  // 后台未声明剪贴板：弹窗无意义，直接进手动复制页
  if (parsed.type === 'not_declared') {
    wx.showToast({ title: '请手动复制链接', icon: 'none', duration: 2000 });
    setTimeout(() => openCopyLinkPage(link, parsed.text, linkType || 'default', platform), 300);
    return;
  }
  showCopyFailModal(link, linkType, platform, parsed, context);
}

function showCopyFailModal(link, linkType, platform, parsed, context) {
  const type = linkType || 'default';
  const recent = dbg.getRecentLogText();
  const detail = [
    parsed.text,
    context ? `阶段: ${context}` : '',
    parsed.raw ? `微信: ${parsed.raw}` : '',
    recent ? `\n--- 日志 ---\n${recent}` : '',
  ].filter(Boolean).join('\n');
  dbg.push('showCopyFailModal', { context, raw: parsed.raw });
  wx.showModal({
    title: '未能复制',
    content: detail.slice(0, 500),
    confirmText: '查看链接',
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) openCopyLinkPage(link, parsed.text, type, platform);
    },
  });
}

/** 授权通过后写剪贴板 */
function writeClipboardDirect(link, linkType, platform) {
  const type = linkType || 'default';
  dbg.push('setClipboardData.start', { len: (link || '').length, type, platform });
  wx.setClipboardData({
    data: link,
    success: () => {
      dbg.push('setClipboardData.success');
      markPrivacyAgreed();
      showCopySuccessGuide(link, type, platform);
    },
    fail: (err) => {
      const parsed = parseCopyError(err);
      dbg.push('setClipboardData.fail', parsed.raw);
      handleCopyFail(link, type, platform, parsed, 'setClipboardData');
    },
  });
}

/** 先走隐私授权，再复制 */
function requestClipboardCopy(link, linkType, platform) {
  const type = linkType || 'default';
  const runCopy = () => writeClipboardDirect(link, type, platform);

  if (typeof wx.requirePrivacyAuthorize !== 'function') {
    dbg.push('requirePrivacyAuthorize.unsupported');
    runCopy();
    return;
  }

  dbg.push('requirePrivacyAuthorize.start');
  wx.requirePrivacyAuthorize({
    success: () => {
      dbg.push('requirePrivacyAuthorize.success');
      runCopy();
    },
    fail: (err) => {
      const parsed = parseCopyError(err);
      dbg.push('requirePrivacyAuthorize.fail', parsed.raw);
      openCopyLinkPage(link, parsed.text || '用户拒绝隐私授权', type, platform);
    },
  });
}

/**
 * 复制购买链接（商品分析页「去购买」入口）。
 * @param {string} [platform] jd | pdd，用于展示正确打开方式
 */
function copyPurchaseLink(link, linkType, platform) {
  if (!link) {
    wx.showToast({ title: '暂无购买链接', icon: 'none' });
    return;
  }
  const type = linkType || 'default';
  const app = getApp();
  if (app && app.globalData) {
    app.globalData.pendingPurchase = { link, linkType: type, platform: platform || '' };
    app.globalData.clipboardDebugLog = [];
  }
  dbg.push('copyPurchaseLink.start', { type, platform, linkPreview: link.slice(0, 60) });
  if (typeof wx.getPrivacySetting === 'function') {
    wx.getPrivacySetting({
      success: (res) => dbg.push('getPrivacySetting', res),
      fail: (err) => dbg.push('getPrivacySetting.fail', dbg.formatErr(err)),
    });
  }
  requestClipboardCopy(link, type, platform);
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
  writeClipboard: writeClipboardDirect,
  markPrivacyAgreed,
  openCopyLinkPage,
  parseCopyError,
  requestClipboardCopy,
  buildPurchaseGuide,
};
