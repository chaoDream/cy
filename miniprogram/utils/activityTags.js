/** 数据源占位标签，不应展示给用户 */
const INTERNAL_ACTIVITY_TAGS = new Set([
  '京东联盟',
  '维易',
  '维易·京东联盟',
  '多多进宝',
]);

/** 过滤内部占位标签，保留真实活动/营销标签 */
function sanitizeActivityTags(tags) {
  return (tags || [])
    .map((t) => String(t).trim())
    .filter((t) => t && !INTERNAL_ACTIVITY_TAGS.has(t));
}

module.exports = { sanitizeActivityTags, INTERNAL_ACTIVITY_TAGS };
