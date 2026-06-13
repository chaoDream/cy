const api = require('../../../api/index');
const track = require('../../../utils/track');
const { ensureLogin } = require('../../../utils/auth');

Page(track.mergePage({
  data: {
    mode: 'notice', // notice | feedback
    rawProductId: '',
    feedbackTypes: [
      { key: 'price_wrong', label: '到手价不对' },
      { key: 'sku_wrong', label: '型号识别错误' },
      { key: 'risk_wrong', label: '风险标签有误' },
      { key: 'other', label: '其他问题' },
    ],
    typeIndex: 0,
    content: '',
  },

  onLoad(query) {
    if (query.mode === 'feedback') {
      this.setData({ mode: 'feedback', rawProductId: query.raw || '' });
      wx.setNavigationBarTitle({ title: '纠错反馈' });
    }
  },

  onTypeTap(e) {
    this.setData({ typeIndex: Number(e.currentTarget.dataset.idx) });
  },

  onContentInput(e) {
    this.setData({ content: e.detail.value });
  },

  onSubmit() {
    ensureLogin()
      .then(() =>
        api.feedback({
          rawProductId: this.data.rawProductId ? Number(this.data.rawProductId) : null,
          feedbackType: this.data.feedbackTypes[this.data.typeIndex].key,
          feedbackContent: this.data.content,
        }),
      )
      .then(() => {
        track.event('feedback_submit');
        wx.showToast({ title: '感谢反馈，我们会尽快核实', icon: 'none' });
        setTimeout(() => wx.navigateBack(), 1200);
      })
      .catch((err) => wx.showToast({ title: err.message || '提交失败', icon: 'none' }));
  },
}, track.pageMixin('data-notice')));
