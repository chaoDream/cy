const PRIVACY_OK_KEY = '__clipboard_privacy_ok__';

Component({
  data: {
    visible: false,
    privacyContractName: '用户隐私保护指引',
  },

  lifetimes: {
    attached() {
      this._resolve = null;
      if (typeof wx.getPrivacySetting === 'function') {
        wx.getPrivacySetting({
          success: (res) => {
            if (res.privacyContractName) {
              this.setData({ privacyContractName: res.privacyContractName });
            }
          },
        });
      }
      if (typeof wx.onNeedPrivacyAuthorization === 'function') {
        wx.onNeedPrivacyAuthorization((resolve) => {
          this._resolve = resolve;
          this.setData({ visible: true });
        });
      }
    },
  },

  methods: {
    noop() {},

    openContract() {
      if (wx.openPrivacyContract) {
        wx.openPrivacyContract({});
      }
    },

    onAgree() {
      try {
        wx.setStorageSync(PRIVACY_OK_KEY, '1');
      } catch (e) {
        // ignore
      }
      const app = getApp();
      if (app && app.globalData) {
        app.globalData.clipboardPrivacyOk = true;
      }
      if (typeof this._resolve === 'function') {
        this._resolve({ event: 'agree', buttonId: 'pp-agree' });
        this._resolve = null;
      }
      this.setData({ visible: false });
    },

    onDisagree() {
      if (typeof this._resolve === 'function') {
        this._resolve({ event: 'disagree' });
        this._resolve = null;
      }
      this.setData({ visible: false });
      wx.showToast({ title: '未同意，无法复制链接', icon: 'none' });
    },
  },
});
