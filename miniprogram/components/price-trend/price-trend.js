const { yuanTrim } = require('../../utils/format');

/** YYYY-MM-DD of (today - n days)，用于 30 天区间过滤 */
function isoDaysAgo(n) {
  const d = new Date();
  d.setDate(d.getDate() - n);
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `${d.getFullYear()}-${mm}-${dd}`;
}

function dpr() {
  try {
    return wx.getWindowInfo
      ? wx.getWindowInfo().pixelRatio || 2
      : wx.getSystemInfoSync().pixelRatio || 2;
  } catch (e) {
    return 2;
  }
}

Component({
  properties: {
    trend: { type: Object, value: null },
    current: { type: null, value: null }, // 当前到手价（来自分析页 priceInfo）
  },
  data: {
    range: '90', // '30' | '90'
    curText: '--',
    lowText: '--',
    rangeEmpty: false,
  },
  lifetimes: {
    ready() {
      this._ready = true;
      this._prepare();
      this._render();
    },
  },
  observers: {
    'trend, current': function () {
      this._prepare();
      this._render();
    },
  },
  methods: {
    onSwitchRange(e) {
      const r = e.currentTarget.dataset.r;
      if (r === this.data.range) return;
      this.setData({ range: r });
      this._prepare();
      this._render();
    },

    // 根据当前区间算出可见点 / 参考低价 / 文案，结果缓存到 this._vis、this._low
    _prepare() {
      const t = this.properties.trend;
      if (!t || t.historyInsufficient) {
        this._vis = [];
        return;
      }
      const range = this.data.range;
      const all = (t.points || []).map((p) => ({
        label: String(p.date).slice(5),
        date: String(p.date),
        p: Number(p.price),
      }));

      let vis = all;
      if (range === '30') {
        const cutoff = isoDaysAgo(30);
        vis = all.filter((x) => x.date >= cutoff);
        if (!vis.length && all.length) vis = all.slice(-1);
      }

      const low = range === '30' ? Number(t.low30) : Number(t.low90);
      const cur = this.properties.current != null && this.properties.current !== ''
        ? Number(this.properties.current)
        : (vis.length ? vis[vis.length - 1].p : 0);

      this._vis = vis;
      this._low = low;
      this.setData({
        lowText: low > 0 ? yuanTrim(low) : '--',
        curText: cur > 0 ? yuanTrim(cur) : '--',
        rangeEmpty: !vis.length,
      });
    },

    _render() {
      if (!this._ready) return;
      const t = this.properties.trend;
      if (!t || t.historyInsufficient || !this._vis || !this._vis.length) return;

      const q = this.createSelectorQuery();
      q.select('#ptCanvas')
        .fields({ node: true, size: true })
        .exec((res) => {
          const info = res && res[0];
          if (!info || !info.node) {
            // 布局未就绪时重试一次
            if (!this._retried) {
              this._retried = true;
              setTimeout(() => this._render(), 60);
            }
            return;
          }
          this._retried = false;
          const canvas = info.node;
          const ctx = canvas.getContext('2d');
          const ratio = dpr();
          canvas.width = info.width * ratio;
          canvas.height = info.height * ratio;
          ctx.scale(ratio, ratio);
          this._paint(ctx, info.width, info.height);
        });
    },

    _paint(ctx, W, H) {
      ctx.clearRect(0, 0, W, H);
      const pts = this._vis;
      const low = this._low;
      if (!pts || !pts.length) return;

      const padL = 12;
      const padR = 14;
      const padT = 18;
      const padB = 22;
      const plotW = W - padL - padR;
      const plotH = H - padT - padB;

      const prices = pts.map((p) => p.p);
      let min = Math.min.apply(null, low > 0 ? prices.concat(low) : prices);
      let max = Math.max.apply(null, prices);
      const span = max - min || 1;
      min -= span * 0.12; // 动态留白，Y 轴不从 0 起
      max += span * 0.12;

      const n = pts.length;
      const X = (i) => (n === 1 ? padL + plotW / 2 : padL + (plotW * i) / (n - 1));
      const Y = (v) => padT + plotH * (1 - (v - min) / (max - min));

      // 参考线：当前区间的最低价
      if (low > 0) {
        ctx.save();
        ctx.setLineDash([4, 4]);
        ctx.globalAlpha = 0.75;
        ctx.lineWidth = 1;
        ctx.strokeStyle = this.data.range === '30' ? '#4e93ff' : '#b37feb';
        ctx.beginPath();
        ctx.moveTo(padL, Y(low));
        ctx.lineTo(W - padR, Y(low));
        ctx.stroke();
        ctx.restore();
      }

      // 面积填充
      const grad = ctx.createLinearGradient(0, padT, 0, padT + plotH);
      grad.addColorStop(0, 'rgba(255,80,0,0.18)');
      grad.addColorStop(1, 'rgba(255,80,0,0)');
      ctx.beginPath();
      ctx.moveTo(X(0), padT + plotH);
      pts.forEach((p, i) => ctx.lineTo(X(i), Y(p.p)));
      ctx.lineTo(X(n - 1), padT + plotH);
      ctx.closePath();
      ctx.fillStyle = grad;
      ctx.fill();

      // 折线
      ctx.beginPath();
      pts.forEach((p, i) => (i ? ctx.lineTo(X(i), Y(p.p)) : ctx.moveTo(X(i), Y(p.p))));
      ctx.strokeStyle = '#ff5000';
      ctx.lineWidth = 2;
      ctx.lineJoin = 'round';
      ctx.lineCap = 'round';
      ctx.stroke();

      ctx.font = '10px sans-serif';

      // 历史最低点
      const minVal = Math.min.apply(null, prices);
      const minIdx = prices.indexOf(minVal);
      ctx.beginPath();
      ctx.arc(X(minIdx), Y(minVal), 3.5, 0, Math.PI * 2);
      ctx.fillStyle = '#fff';
      ctx.fill();
      ctx.lineWidth = 2;
      ctx.strokeStyle = '#00a86b';
      ctx.stroke();
      ctx.fillStyle = '#00a86b';
      ctx.textAlign = minIdx === 0 ? 'left' : minIdx === n - 1 ? 'right' : 'center';
      ctx.fillText(`最低 ¥${Math.round(minVal)}`, X(minIdx), Y(minVal) + 15);

      // 当前价（末点）
      const li = n - 1;
      ctx.beginPath();
      ctx.arc(X(li), Y(prices[li]), 4, 0, Math.PI * 2);
      ctx.fillStyle = '#ff5000';
      ctx.fill();
      ctx.lineWidth = 2;
      ctx.strokeStyle = '#fff';
      ctx.stroke();
      // 当前点标签放在点上方，避免和「最低」标签重叠
      ctx.fillStyle = '#ff5000';
      ctx.textAlign = li === 0 ? 'left' : 'right';
      ctx.fillText(`当前 ¥${Math.round(prices[li])}`, X(li), Y(prices[li]) - 9);

      // X 轴稀疏日期（首 / 中 / 尾）
      ctx.fillStyle = '#c0c4cc';
      const idxs = n === 1 ? [0] : [0, Math.floor(li / 2), li];
      idxs.forEach((i) => {
        ctx.textAlign = i === 0 ? 'left' : i === li ? 'right' : 'center';
        ctx.fillText(pts[i].label, X(i), H - 6);
      });
    },
  },
});
