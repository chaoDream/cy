const { baseUrl } = require('./config');

const TOKEN_KEY = 'token';

function getToken() {
  return wx.getStorageSync(TOKEN_KEY) || '';
}

function setToken(token) {
  wx.setStorageSync(TOKEN_KEY, token);
}

/**
 * 统一请求封装：
 * - 自动带 Authorization
 * - 统一解包 {code,message,data}，code==0 视为成功
 * - 401 时清除登录态并尝试重新登录（由调用方决定是否重试）
 */
function request(options) {
  const { url, method = 'GET', data = {}, showLoading = false, auth = true } = options;

  return new Promise((resolve, reject) => {
    if (showLoading) wx.showLoading({ title: '加载中', mask: true });

    const header = { 'content-type': 'application/json' };
    if (auth) {
      const token = getToken();
      if (token) header.Authorization = `Bearer ${token}`;
    }

    wx.request({
      url: `${baseUrl}${url}`,
      method,
      data,
      header,
      success(res) {
        const body = res.data || {};
        if (res.statusCode === 200 && body.code === 0) {
          resolve(body.data);
        } else if (body.code === 1401 || res.statusCode === 401) {
          wx.removeStorageSync(TOKEN_KEY);
          reject({ code: 1401, message: '登录已过期', needLogin: true });
        } else {
          reject({ code: body.code || res.statusCode, message: body.message || '请求失败' });
        }
      },
      fail(err) {
        reject({ code: -1, message: err.errMsg || '网络异常' });
      },
      complete() {
        if (showLoading) wx.hideLoading();
      },
    });
  });
}

module.exports = { request, getToken, setToken };
