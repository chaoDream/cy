Page({
  data: { url: '' },
  onLoad(query) {
    if (query.url) {
      this.setData({ url: decodeURIComponent(query.url) });
    }
  },
});
