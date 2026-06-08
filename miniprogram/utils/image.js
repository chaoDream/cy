const { resolveImageUrl } = require('./format');

/**
 * 将商品图 URL 转为 image 组件可用的 src。
 * 真机对 http:// 局域网直链限制比 wx.request 更严，先 downloadFile 到本地临时路径再展示。
 */
function prepareImageForDisplay(url) {
  return new Promise((resolve) => {
    if (!url) {
      resolve('');
      return;
    }
    const full = resolveImageUrl(url);
    if (!full) {
      resolve('');
      return;
    }
    // 已是 https 或本地临时文件，可直接用
    if (full.startsWith('https://') || full.startsWith('wxfile://')) {
      resolve(full);
      return;
    }
    wx.downloadFile({
      url: full,
      success(res) {
        if (res.statusCode === 200 && res.tempFilePath) {
          resolve(res.tempFilePath);
        } else {
          console.warn('[image] downloadFile bad status', res.statusCode, full);
          resolve(full);
        }
      },
      fail(err) {
        console.warn('[image] downloadFile fail', full, err);
        resolve(full);
      },
    });
  });
}

module.exports = { prepareImageForDisplay };
