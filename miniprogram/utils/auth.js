const { request, getToken, setToken } = require('./request');

const USER_INFO_KEY = 'userInfo';

function getUserInfo() {
  return wx.getStorageSync(USER_INFO_KEY) || {};
}

function setUserInfo(info) {
  wx.setStorageSync(USER_INFO_KEY, info || {});
}

function clearUserInfo() {
  wx.removeStorageSync(USER_INFO_KEY);
}

/**
 * 静默登录：仅 wx.login + code 换 token（无昵称头像，启动时用）
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
            if (data.nickname || data.avatarUrl) {
              setUserInfo({ nickname: data.nickname, avatarUrl: data.avatarUrl });
            }
            resolve(data);
          })
          .catch(reject);
      },
      fail: reject,
    });
  });
}

/**
 * 用户点击登录：先弹微信授权拿昵称头像，再 wx.login 换 token。
 * 必须在按钮 bindtap 里直接调用（微信要求用户主动触发）。
 */
function loginWithProfile() {
  return new Promise((resolve, reject) => {
    wx.getUserProfile({
      desc: '用于展示你的昵称和头像',
      success(profileRes) {
        const { nickName, avatarUrl } = profileRes.userInfo;
        wx.login({
          success(loginRes) {
            if (!loginRes.code) {
              reject(new Error('wx.login 未返回 code'));
              return;
            }
            request({
              url: '/api/user/login',
              method: 'POST',
              data: {
                code: loginRes.code,
                nickname: nickName,
                avatarUrl,
              },
              auth: false,
            })
              .then((data) => {
                setToken(data.token);
                const userInfo = {
                  nickname: data.nickname || nickName,
                  avatarUrl: data.avatarUrl || avatarUrl,
                };
                setUserInfo(userInfo);
                resolve({ ...data, ...userInfo });
              })
              .catch(reject);
          },
          fail: reject,
        });
      },
      fail(err) {
        reject({ code: -2, message: '需要授权昵称和头像才能登录', raw: err });
      },
    });
  });
}

/** 静默登录：已有 token 则跳过 */
function silentLogin() {
  if (getToken()) return Promise.resolve({ token: getToken(), ...getUserInfo() });
  return login();
}

/** 确保已登录（受保护操作前），不要求昵称头像 */
function ensureLogin() {
  if (getToken()) return Promise.resolve();
  return login().then(() => undefined);
}

module.exports = {
  login,
  loginWithProfile,
  silentLogin,
  ensureLogin,
  getUserInfo,
  setUserInfo,
  clearUserInfo,
};
