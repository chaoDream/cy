const shareConfig = {
  search: {
    title: '别被标价骗了！这个工具帮你算出真实到手价',
    path: '/pages/search/search',
  },
  rank: {
    title: '全网比价后，这些才是真便宜的！',
    path: '/pages/rank/rank',
  },
  watch: {
    title: '我在用这个盯价神器，降价第一时间通知我',
    path: '/pages/search/search',
  },
  mine: {
    title: '买贵了？先用这个查查真实到手价再下单',
    path: '/pages/search/search',
  },
};

function getShareMessage(page) {
  const cfg = shareConfig[page] || shareConfig.search;
  return { title: cfg.title, path: cfg.path };
}

function getShareTimeline(page) {
  const cfg = shareConfig[page] || shareConfig.search;
  return { title: cfg.title };
}

function getAnalysisShareMessage(productTitle, priceText, platform, itemId) {
  return {
    title: `帮你查了「${productTitle}」，算完优惠券到手只要 ¥${priceText}！`,
    path: `/packageA/pages/analysis/analysis?platform=${platform}&itemId=${itemId}`,
  };
}

function getAnalysisShareTimeline(productTitle, priceText, platform, itemId) {
  return {
    title: `「${productTitle}」到手只要 ¥${priceText}！别买贵了`,
    query: `platform=${platform}&itemId=${itemId}`,
  };
}

module.exports = {
  shareConfig,
  getShareMessage,
  getShareTimeline,
  getAnalysisShareMessage,
  getAnalysisShareTimeline,
};
