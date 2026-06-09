/**
 * 剪贴板 / 隐私授权诊断日志。
 * 真机调试：微信开发者工具 → 真机调试 → 开启 vConsole → 过滤 [CLIPBOARD]
 */

const TAG = '[CLIPBOARD]';
const MAX = 30;

function push(step, detail) {
  const entry = {
    t: Date.now(),
    step,
    detail: detail == null ? '' : String(typeof detail === 'object' ? JSON.stringify(detail) : detail),
  };
  try {
    const app = getApp();
    if (app && app.globalData) {
      if (!Array.isArray(app.globalData.clipboardDebugLog)) {
        app.globalData.clipboardDebugLog = [];
      }
      app.globalData.clipboardDebugLog.push(entry);
      if (app.globalData.clipboardDebugLog.length > MAX) {
        app.globalData.clipboardDebugLog.shift();
      }
      app.globalData.lastClipboardError = entry;
    }
  } catch (e) {
    // ignore
  }
  console.log(TAG, step, detail == null ? '' : detail);
  try {
    if (typeof wx.getRealtimeLogManager === 'function') {
      wx.getRealtimeLogManager().info(TAG, step, detail == null ? '' : detail);
    }
  } catch (e) {
    // ignore
  }
}

function formatErr(err) {
  if (!err) return 'unknown';
  if (typeof err === 'string') return err;
  return err.errMsg || err.message || JSON.stringify(err);
}

function getRecentLogText() {
  try {
    const app = getApp();
    const logs = (app && app.globalData && app.globalData.clipboardDebugLog) || [];
    return logs.slice(-8).map((e) => `${e.step}${e.detail ? `: ${e.detail}` : ''}`).join('\n');
  } catch (e) {
    return '';
  }
}

module.exports = { push, formatErr, getRecentLogText, TAG };
