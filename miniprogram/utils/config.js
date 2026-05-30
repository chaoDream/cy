// 后端地址：上线前需在「小程序后台 → 开发设置 → 服务器域名」配置 request 合法域名（https）
const ENV = 'dev';

const CONFIG = {
  dev: {
    // 开发者工具可勾选"不校验合法域名"；真机需 https
    baseUrl: 'http://localhost:8080',
  },
  prod: {
    baseUrl: 'https://api.zhendaoshoujia.com',
  },
};

module.exports = {
  baseUrl: CONFIG[ENV].baseUrl,
  // 微信订阅消息模板 id（降价提醒），需与后台一致
  subscribeTemplateId: 'dev_template_id',
};
