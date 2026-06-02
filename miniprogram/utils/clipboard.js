/**
 * 剪贴板识别（PRD §7.1 核心入口）：
 * 读取剪贴板，若包含京东/拼多多链接或淘口令，返回文本供首页提示「检测到链接」。
 */
const PLATFORM_HINTS = ['jd.com', '京东', '3.cn', 'pinduoduo', 'yangkeduo', 'mobile.yangkeduo', '拼多多', 'item.jd', 'http'];

function detectFromClipboard() {
  return new Promise((resolve) => {
    wx.getClipboardData({
      success(res) {
        const text = (res.data || '').trim();
        if (text && PLATFORM_HINTS.some((h) => text.includes(h))) {
          resolve(text);
        } else {
          resolve('');
        }
      },
      fail() {
        resolve('');
      },
    });
  });
}

module.exports = { detectFromClipboard };
