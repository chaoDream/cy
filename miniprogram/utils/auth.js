const { request, getToken, setToken } = require('./request');

/**
 * 微信一键登录（PRD §4 账户）：
 * wx.login 取 code → 后端 code2session 换 openid + 自定义登录态 token。
 */
function login() {
  return new Promise((resolve, reject) => {
    wx.login({
      success(res) {
        if (!res.code) {
          reject(new Error('wx.login 未返回 code'));
          return;
        }
        request({
          url: '/api/user/login',
          method: 'POST',
          data: { code: res.code },
          auth: false,
        })
          .then((data) => {
            setToken(data.token);
            resolve(data);
          })
          .catch(reject);
      },
      fail: reject,
    });
  });
}

/** 静默登录：已有 token 则跳过 */
function silentLogin() {
  if (getToken()) return Promise.resolve({ token: getToken() });
  return login();
}

/** 确保已登录（受保护操作前调用），未登录则发起登录 */
function ensureLogin() {
  if (getToken()) return Promise.resolve();
  return login().then(() => undefined);
}

module.exports = { login, silentLogin, ensureLogin };
