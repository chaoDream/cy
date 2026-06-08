-- 盯价范围开关：merchant=只盯当前商家这条链接；platform_lowest=盯该平台同款所有商家的最低价
ALTER TABLE watch_item
    ADD COLUMN watch_mode VARCHAR(16) NOT NULL DEFAULT 'merchant';
