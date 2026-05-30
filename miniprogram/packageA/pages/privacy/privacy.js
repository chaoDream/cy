Page({
  // 上线前应接入微信小程序「用户隐私保护指引」并在 app.json 开启 __usePrivacyCheck__
  openOfficialPrivacy() {
    if (wx.openPrivacyContract) {
      wx.openPrivacyContract({
        fail: () => wx.showToast({ title: '请在小程序后台配置隐私协议', icon: 'none' }),
      });
    }
  },
});
