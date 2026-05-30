-- W7 冷启动：热门机型 SPU/SKU 入库 + 示例促销规则
-- 实际运营由后台维护，这里给出初始种子，便于低价榜与同款比价冷启动。

INSERT INTO product_spu (brand, series, model, official_price, status) VALUES
  ('Apple', 'iPhone 16', 'iPhone 16 Pro', 8999, 'active'),
  ('Apple', 'iPhone 16', 'iPhone 16', 5999, 'active'),
  ('华为', 'Mate 70', 'Mate 70 Pro', 6499, 'active'),
  ('小米', '15', '15 Pro', 5299, 'active'),
  ('vivo', 'X200', 'X200 Pro', 5999, 'active');

INSERT INTO product_sku (brand, series, model, storage, color, network_version, region_version, condition, package_type, standard_name) VALUES
  ('Apple', 'iPhone 16', 'iPhone 16 Pro', '256GB', '原色钛金属', '5G', '国行', '全新', '裸机', 'Apple iPhone 16 Pro 256GB 国行'),
  ('Apple', 'iPhone 16', 'iPhone 16', '128GB', '群青色', '5G', '国行', '全新', '裸机', 'Apple iPhone 16 128GB 国行'),
  ('华为', 'Mate 70', 'Mate 70 Pro', '256GB', '雪域白', '5G', '国行', '全新', '裸机', '华为 Mate 70 Pro 256GB 国行'),
  ('小米', '15', '15 Pro', '512GB', '黑色', '5G', '国行', '全新', '裸机', '小米 15 Pro 512GB 国行'),
  ('vivo', 'X200', 'X200 Pro', '512GB', '钛色', '5G', '国行', '全新', '裸机', 'vivo X200 Pro 512GB 国行');

-- 示例促销规则（结构化，供规则引擎扩展使用）
INSERT INTO promotion_rule (platform, rule_type, rule_json, valid_from, valid_to) VALUES
  ('jd', 'platform_coupon', '{"threshold": 4000, "discount": 200}', now() - interval '7 days', now() + interval '30 days'),
  ('jd', 'member', '{"asset": "jdPlus", "rate": 0.01}', now() - interval '7 days', now() + interval '30 days'),
  ('pdd', 'subsidy', '{"name": "百亿补贴", "type": "official"}', now() - interval '7 days', now() + interval '30 days'),
  ('pdd', 'member', '{"asset": "pddMonthly", "rate": 0.01}', now() - interval '7 days', now() + interval '30 days');
