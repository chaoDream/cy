function yuan(v) {
  if (v === null || v === undefined || v === '') return '--';
  const n = Number(v);
  if (Number.isNaN(n)) return '--';
  return n.toFixed(2);
}

function platformName(code) {
  return { jd: '京东', pdd: '拼多多', tb: '淘宝', dy: '抖音' }[code] || code;
}

function shopTypeName(type) {
  return {
    self: '自营',
    flagship: '官方旗舰店',
    thirdparty: '第三方店铺',
  }[type] || (type || '');
}

module.exports = { yuan, platformName, shopTypeName };
