const { writeClipboard, markPrivacyAgreed, openCopyLinkPage } = require('../../utils/purchase');

Component({
  data: {
    visible: false,
  },

  lifetimes: {
    attached() {
      this._link = '';
      this._type = '';
      this._resolve = null;
      const app = getApp();
      if (app && app.globalData) {
        app.globalData.privacyPopup = this;
      }
      // 兜底：若某处直接调用了隐私 API 触发原生授权请求，也用本弹窗承接
      if (typeof wx.onNeedPrivacyAuthorization === 'function') {
        wx.onNeedPrivacyAuthorization((resolve) => {
          this._resolve = resolve;
          this.setData({ visible: true });
        });
      }
    },
    detached() {
      const app = getApp();
      if (app && app.globalData && app.globalData.privacyPopup === this) {
        app.globalData.privacyPopup = null;
      }
    },
  },

  methods: {
    noop() {},

    /** 由 purchase.js 主动调用：携带待复制链接弹出 */
    show(link, linkType) {
      this._link = link || '';
      this._type = linkType || 'default';
      this.setData({ visible: true });
    },

    onAgree() {
      markPrivacyAgreed();
      this.setData({ visible: false });
      // 原生挂起的请求（兜底分支）：放行后由原调用自动继续
      if (typeof this._resolve === 'function') {
        this._resolve({ event: 'agree', buttonId: 'pp-agree' });
        this._resolve = null;
        return;
      }
      // 主动弹窗分支：直接复制
      if (this._link) {
        writeClipboard(this._link, this._type);
      }
    },

    onDisagree() {
      this.setData({ visible: false });
      if (typeof this._resolve === 'function') {
        this._resolve({ event: 'disagree' });
        this._resolve = null;
      }
      const link = this._link;
      const type = this._type;
      if (link) {
        openCopyLinkPage(link, '', type);
      } else {
        wx.showToast({ title: '未同意隐私协议', icon: 'none' });
      }
    },
  },
});
