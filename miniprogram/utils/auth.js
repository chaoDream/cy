const { request, getToken, setToken } = require('./request');

const USER_INFO_KEY = 'userInfo';
const LOGGED_IN_KEY = 'hasLoggedIn';

function getUserInfo() {
  return wx.getStorageSync(USER_INFO_KEY) || {};
}

function setUserInfo(info) {
  wx.setStorageSync(USER_INFO_KEY, info || {});
}

function clearUserInfo() {
  wx.removeStorageSync(USER_INFO_KEY);
}

function hasLoggedIn() {
  return !!wx.getStorageSync(LOGGED_IN_KEY);
}

function markLoggedIn() {
  wx.setStorageSync(LOGGED_IN_KEY, true);
}

function clearLoginState() {
  setToken('');
  clearUserInfo();
  wx.removeStorageSync(LOGGED_IN_KEY);
}

function applyLoginResult(data) {
  setToken(data.token);
  markLoggedIn();
  const userInfo = {
    userId: data.userId || '',
    nickname: data.nickname || '',
    avatarUrl: data.avatarUrl || '',
  };
  setUserInfo(userInfo);
  return { ...data, ...userInfo };
}

/** 当前登录用户 id（未登录返回空串）；用于拼多多 custom_parameters 透传 */
function getUserId() {
  const info = getUserInfo();
  return info && info.userId ? String(info.userId) : '';
}

/**
 * 静默登录：wx.login + code 换 token（不传头像昵称，服务端绑定 openid 与固定随机昵称）
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
          .then((data) => resolve(applyLoginResult(data)))
          .catch(reject);
      },
      fail: reject,
    });
  });
}

/** 与 login 相同，用于 token 失效后续登 */
function silentRelogin() {
  return login();
}

/**
 * 启动时恢复登录态：有 token 复用，否则静默 wx.login
 */
function restoreLogin() {
  if (getToken()) {
    return Promise.resolve({ token: getToken(), ...getUserInfo() });
  }
  return login();
}

/** 保证已登录（无 token 时自动静默登录，供盯价等接口使用） */
function ensureLogin() {
  if (getToken()) return Promise.resolve();
  return login().then(() => undefined);
}

module.exports = {
  login,
  silentRelogin,
  restoreLogin,
  silentLogin: restoreLogin,
  ensureLogin,
  getUserInfo,
  getUserId,
  setUserInfo,
  clearUserInfo,
  hasLoggedIn,
  clearLoginState,
};
