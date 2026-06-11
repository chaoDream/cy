const { resolveImageUrl } = require('./format');

function guessExt(url, header) {
  const ct = (header && (header['Content-Type'] || header['content-type'])) || '';
  if (ct.includes('png')) return 'png';
  if (ct.includes('webp')) return 'webp';
  if (url.includes('.png')) return 'png';
  if (url.includes('.webp')) return 'webp';
  return 'jpg';
}

/**
 * 通过 wx.request 拉取图片字节并写入用户目录（与 analysis 等同 request 域，真机 LAN 比 downloadFile 更稳）。
 */
function fetchImageViaRequest(full) {
  return new Promise((resolve) => {
    wx.request({
      url: full,
      method: 'GET',
      responseType: 'arraybuffer',
      success(res) {
        if (res.statusCode !== 200 || !res.data || res.data.byteLength === 0) {
          console.warn('[image] request bad status', res.statusCode, full);
          resolve('');
          return;
        }
        const ext = guessExt(full, res.header);
        const path = `${wx.env.USER_DATA_PATH}/prod_${Date.now()}.${ext}`;
        wx.getFileSystemManager().writeFile({
          filePath: path,
          data: res.data,
          encoding: 'binary',
          success: () => resolve(path),
          fail: (err) => {
            console.warn('[image] writeFile fail', full, err);
            resolve('');
          },
        });
      },
      fail(err) {
        console.warn('[image] request fail', full, err);
        resolve('');
      },
    });
  });
}

/**
 * 将商品图 URL 转为 image 组件可用的 src。
 * - https：可直接展示
 * - http（含局域网 dev）：走 wx.request 写临时文件（downloadFile 在真机上域名校验更严）
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
    if (full.startsWith('wxfile://')) {
      resolve(full);
      return;
    }
    if (full.startsWith('https://')) {
      resolve(full);
      return;
    }
    if (full.startsWith('http://')) {
      fetchImageViaRequest(full).then(resolve);
      return;
    }
    resolve(full);
  });
}

/** 批量处理列表项里的 imageUrl 字段 */
function prepareListImages(list, urlKey = 'imageUrl') {
  if (!list || !list.length) return Promise.resolve(list || []);
  return Promise.all(
    list.map((item) =>
      prepareImageForDisplay(item[urlKey]).then((img) => ({
        ...item,
        [urlKey]: img || item[urlKey],
      })),
    ),
  );
}

module.exports = { prepareImageForDisplay, prepareListImages };
