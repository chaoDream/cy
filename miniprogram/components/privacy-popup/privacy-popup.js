const { requestClipboardCopy, markPrivacyAgreed, openCopyLinkPage } = require('../../utils/purchase');
const dbg = require('../../utils/privacy-debug');

const AGREE_BUTTON_ID = 'pp-agree';

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
        dbg.push('privacyPopup.attached');
        if (typeof app.globalData.pendingPrivacyResolve === 'function') {
          this.handlePrivacyAuth(app.globalData.pendingPrivacyResolve, app.globalData.pendingPrivacyEventInfo);
          app.globalData.pendingPrivacyResolve = null;
          app.globalData.pendingPrivacyEventInfo = null;
        }
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

    handlePrivacyAuth(resolve, eventInfo) {
      this._resolve = resolve;
      const app = getApp();
      if (app && app.globalData) {
        app.globalData.privacyResolve = resolve;
      }
      const pending = app && app.globalData && app.globalData.pendingPurchase;
      if (pending && pending.link) {
        this._link = pending.link;
        this._type = pending.linkType || 'default';
      }
      dbg.push('privacyPopup.show', {
        hasResolve: true,
        referrer: eventInfo && eventInfo.referrer,
        hasLink: !!this._link,
      });
      if (app && app.globalData) {
        app.globalData.privacyModalVisible = true;
      }
      this.setData({ visible: true });
    },

    onAgree() {
      const app = getApp();
      const resolveFn = this._resolve
        || (app && app.globalData && app.globalData.privacyResolve);
      dbg.push('privacyPopup.onAgree', { hasResolve: typeof resolveFn === 'function' });

      this.setData({ visible: false });
      if (app && app.globalData) {
        app.globalData.privacyModalVisible = false;
      }

      if (typeof resolveFn === 'function') {
        try {
          resolveFn({ event: 'agree', buttonId: AGREE_BUTTON_ID });
          dbg.push('privacyPopup.resolve.called', AGREE_BUTTON_ID);
        } catch (e) {
          dbg.push('privacyPopup.resolve.error', dbg.formatErr(e));
        }
        this._resolve = null;
        if (app && app.globalData) {
          app.globalData.privacyResolve = null;
        }
        markPrivacyAgreed();
        this._notifyPrivacySettled();
        return;
      }

      // 无挂起 resolve：requirePrivacyAuthorize 未触发，授权后再试
      dbg.push('privacyPopup.onAgree.noResolve');
      markPrivacyAgreed();
      if (this._link) {
        requestClipboardCopy(this._link, this._type);
      }
      this._notifyPrivacySettled();
    },

    onDisagree() {
      const app = getApp();
      const resolveFn = this._resolve
        || (app && app.globalData && app.globalData.privacyResolve);
      dbg.push('privacyPopup.onDisagree', { hasResolve: typeof resolveFn === 'function' });
      this.setData({ visible: false });
      if (app && app.globalData) {
        app.globalData.privacyModalVisible = false;
      }
      if (typeof resolveFn === 'function') {
        resolveFn({ event: 'disagree' });
        this._resolve = null;
        if (app && app.globalData) {
          app.globalData.privacyResolve = null;
        }
      }
      const link = this._link;
      const type = this._type;
      if (link) {
        openCopyLinkPage(link, '', type);
      } else {
        wx.showToast({ title: '未同意隐私协议', icon: 'none' });
      }
      this._notifyPrivacySettled();
    },

    _notifyPrivacySettled() {
      const app = getApp();
      const fn = app && app.globalData && app.globalData.onPrivacySettled;
      if (typeof fn === 'function') {
        wx.nextTick(fn);
      }
    },
  },
});
