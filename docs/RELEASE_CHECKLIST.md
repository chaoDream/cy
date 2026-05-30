# W8 上线清单（灰度 / 提审 / 冷启动 / 复盘）

## 一、提审前置资质

- [ ] 企业主体小程序账号（个人号无法用比价/导购类目）。
- [ ] 服务类目：选择「电商平台 / 导购」相关类目并提交资质（比价类需额外说明）。
- [ ] 隐私协议：小程序后台「用户隐私保护指引」配置完成，`app.json` 已开启 `__usePrivacyCheck__: true`。
- [ ] 用户协议、隐私政策、数据来源说明、CPS 披露、价格免责声明在小程序内可访问（已内置「我的 → 数据说明 / 隐私政策」）。
- [ ] request 合法域名：后台「开发设置」配置后端 https 域名；`utils/config.js` 切到 `prod`。
- [ ] 订阅消息模板：申请「降价提醒」一次性订阅模板，模板 id 同步到后端 `WX_SUB_TEMPLATE` 与小程序 `config.subscribeTemplateId`。

## 二、联盟与后端

- [ ] 多多进宝推手 + 京东联盟基础 API 审核通过，PID / appKey 配置到后端环境变量。
- [ ] 关闭 mock：`AFFILIATE_MOCK=false`、`AI_MOCK=false`（或保留 AI mock 控成本）。
- [ ] `JWT_SECRET` 替换为 ≥32 字节随机串；数据库密码改强密码。
- [ ] 真实大模型 API key、`AI_MODEL_HIGH` / `AI_MODEL_FAST` 配置。
- [ ] PostgreSQL / Redis 生产实例 + 备份策略；Flyway 在生产首启自动建表。
- [ ] 价格快照轮询 cron 与限频阈值按真实联盟配额调整（`zdsj.watch.*` / RateLimiter）。

## 三、灰度发布

- [ ] 体验版自测：跑通验收标准（见 PRD §15 与 README）。
- [ ] 提交审核 → 通过后先发布，用「灰度发布」按 1% → 10% → 100% 放量。
- [ ] 监控后端错误率、解析成功率、AI 降级率、联盟限频命中率。

## 四、冷启动（数码/极客社群）

- [ ] 后台人工维护 20–50 个热门 SKU（已提供 V2 种子，可补充）。
- [ ] 准备 3–5 篇「真实到手价 vs 标价」对比内容用于社群分发。
- [ ] 分享卡片打通（分析页 `onShareAppMessage` 已实现）。

## 五、每日复盘与指标看板（PRD §12）

主北极星：完成下单跳转次数 / 周活。辅：盯价提醒打开率 + 次周留存。

埋点已落库到 `track_event`，可用如下 SQL 做简易看板：

```sql
-- 漏斗：app_open → link_parse_submit → analysis_view → watch_create / purchase_click
SELECT event, count(*) FROM track_event
WHERE created_at >= now() - interval '1 day'
GROUP BY event ORDER BY count(*) DESC;

-- 解析成功率
SELECT
  count(*) FILTER (WHERE event='link_parse_success')::float
  / NULLIF(count(*) FILTER (WHERE event='link_parse_submit'),0) AS parse_success_rate
FROM track_event WHERE created_at >= now() - interval '7 days';

-- 查询后关注比例
SELECT
  count(DISTINCT user_id) FILTER (WHERE event='watch_create')::float
  / NULLIF(count(DISTINCT user_id) FILTER (WHERE event='analysis_view'),0) AS watch_rate
FROM track_event WHERE created_at >= now() - interval '7 days';
```

## 六、上线 30 天成功指标（PRD §15.6）

- [ ] 真实查询用户 1000+
- [ ] 解析成功率 70%+
- [ ] 结果页停留 30s+
- [ ] 查询后关注比例 15%+
- [ ] 7 日重复查询 20%+
- [ ] 建议有帮助反馈 60%+
