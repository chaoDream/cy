-- 真到手价 MVP 初始库表（对齐 PRD §10）
-- PostgreSQL 16

-- ============ 用户 ============
CREATE TABLE app_user (
    id          BIGSERIAL PRIMARY KEY,
    openid      VARCHAR(64) NOT NULL UNIQUE,
    nickname    VARCHAR(64),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 省钱资产库：88VIP/PLUS/省钱月卡/国补省市，以 JSON 存储自申报资产
CREATE TABLE user_profile (
    user_id     BIGINT PRIMARY KEY REFERENCES app_user (id),
    assets_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    region      VARCHAR(64),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 商品 / SKU ============
-- SPU：品牌系列型号
CREATE TABLE product_spu (
    id              BIGSERIAL PRIMARY KEY,
    brand           VARCHAR(64) NOT NULL,
    series          VARCHAR(64),
    model           VARCHAR(128) NOT NULL,
    official_price  NUMERIC(12, 2),
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 平台原始商品（联盟 API 返回）
CREATE TABLE product_raw (
    id                BIGSERIAL PRIMARY KEY,
    platform          VARCHAR(16) NOT NULL,           -- jd / pdd
    platform_item_id  VARCHAR(64) NOT NULL,
    title             VARCHAR(512) NOT NULL,
    image_url         VARCHAR(512),
    shop_name         VARCHAR(128),
    shop_type         VARCHAR(32),                    -- self/flagship/thirdparty
    raw_price         NUMERIC(12, 2),
    coupon_info       JSONB DEFAULT '{}'::jsonb,
    activity_tags     JSONB DEFAULT '[]'::jsonb,
    source_url        VARCHAR(1024),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (platform, platform_item_id)
);

-- 标准 SKU（同款比价地基）
CREATE TABLE product_sku (
    id              BIGSERIAL PRIMARY KEY,
    brand           VARCHAR(64) NOT NULL,
    series          VARCHAR(64),
    model           VARCHAR(128) NOT NULL,
    storage         VARCHAR(32),
    color           VARCHAR(32),
    network_version VARCHAR(32),                      -- 5G/4G/全网通
    region_version  VARCHAR(16),                      -- 国行/港版
    condition       VARCHAR(16) NOT NULL DEFAULT '全新', -- 全新/二手/翻新
    package_type    VARCHAR(16) NOT NULL DEFAULT '裸机', -- 裸机/套装
    standard_name   VARCHAR(256) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_sku_model ON product_sku (brand, model, storage);

-- 原始商品 → 标准 SKU 映射（含置信度 / 风险标签 / AI 理由）
CREATE TABLE product_mapping (
    id              BIGSERIAL PRIMARY KEY,
    raw_product_id  BIGINT NOT NULL REFERENCES product_raw (id),
    sku_id          BIGINT REFERENCES product_sku (id),
    confidence      VARCHAR(8) NOT NULL DEFAULT 'low', -- high/mid/low
    risk_tags       JSONB DEFAULT '[]'::jsonb,
    ai_reason       TEXT,
    review_status   VARCHAR(16) NOT NULL DEFAULT 'pending', -- pending/approved/rejected
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (raw_product_id)
);
CREATE INDEX idx_mapping_sku ON product_mapping (sku_id);
CREATE INDEX idx_mapping_review ON product_mapping (review_status);

-- ============ 价格快照（时序，第一天就攒） ============
CREATE TABLE price_snapshot (
    id                     BIGSERIAL PRIMARY KEY,
    raw_product_id         BIGINT NOT NULL REFERENCES product_raw (id),
    sku_id                 BIGINT REFERENCES product_sku (id),
    platform               VARCHAR(16) NOT NULL,
    display_price          NUMERIC(12, 2) NOT NULL,
    estimated_final_price  NUMERIC(12, 2) NOT NULL,    -- 规则引擎到手价
    coupon_amount          NUMERIC(12, 2) DEFAULT 0,
    subsidy_amount         NUMERIC(12, 2) DEFAULT 0,
    freight                NUMERIC(12, 2) DEFAULT 0,
    rebate_estimate        NUMERIC(12, 2) DEFAULT 0,
    uncertainty_flags      JSONB DEFAULT '[]'::jsonb,
    captured_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_snapshot_sku_time ON price_snapshot (sku_id, captured_at DESC);
CREATE INDEX idx_snapshot_raw_time ON price_snapshot (raw_product_id, captured_at DESC);

-- 促销规则（平台券 / 满减 / 补贴等结构化规则）
CREATE TABLE promotion_rule (
    id          BIGSERIAL PRIMARY KEY,
    platform    VARCHAR(16) NOT NULL,
    rule_type   VARCHAR(32) NOT NULL,                  -- platform_coupon/shop_coupon/cross_shop/subsidy/member
    rule_json   JSONB NOT NULL,
    valid_from  TIMESTAMPTZ,
    valid_to    TIMESTAMPTZ
);
CREATE INDEX idx_promo_platform ON promotion_rule (platform, rule_type);

-- ============ 盯价 / 提醒 ============
CREATE TABLE watch_item (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT NOT NULL REFERENCES app_user (id),
    sku_id          BIGINT REFERENCES product_sku (id),
    raw_product_id  BIGINT NOT NULL REFERENCES product_raw (id),
    target_price    NUMERIC(12, 2) NOT NULL,
    current_price   NUMERIC(12, 2),
    notify_enabled  BOOLEAN NOT NULL DEFAULT TRUE,
    poll_tier       VARCHAR(8) NOT NULL DEFAULT 'user', -- hot/user/normal 漏斗轮询分层
    status          VARCHAR(16) NOT NULL DEFAULT 'watching', -- watching/hit/paused
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (user_id, raw_product_id)
);
CREATE INDEX idx_watch_user ON watch_item (user_id);
CREATE INDEX idx_watch_tier_status ON watch_item (poll_tier, status);

-- 命中记录（用于一次订阅一次下发的复订引导）
CREATE TABLE alert_hit_record (
    id            BIGSERIAL PRIMARY KEY,
    watch_item_id BIGINT NOT NULL REFERENCES watch_item (id),
    hit_price     NUMERIC(12, 2) NOT NULL,
    notified_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_hit_watch ON alert_hit_record (watch_item_id);

-- ============ AI 分析记录（可追溯防幻觉） ============
CREATE TABLE ai_analysis_record (
    id          BIGSERIAL PRIMARY KEY,
    sku_id      BIGINT REFERENCES product_sku (id),
    platform    VARCHAR(16),
    input_json  JSONB NOT NULL,                        -- 喂给模型的结构化事实
    conclusion  VARCHAR(16) NOT NULL,                  -- buy/wait/caution/avoid
    reasons     JSONB NOT NULL,                        -- ≤3 条
    confidence  VARCHAR(8),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_ai_sku ON ai_analysis_record (sku_id, created_at DESC);

-- ============ 反馈 / 运营日志 ============
CREATE TABLE user_feedback (
    id              BIGSERIAL PRIMARY KEY,
    user_id         BIGINT REFERENCES app_user (id),
    raw_product_id  BIGINT REFERENCES product_raw (id),
    feedback_type   VARCHAR(32) NOT NULL,              -- price_wrong/sku_wrong/risk_wrong/other
    feedback_content TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'open', -- open/resolved
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE operation_log (
    id          BIGSERIAL PRIMARY KEY,
    admin_id    BIGINT,
    action      VARCHAR(64) NOT NULL,
    target      VARCHAR(128),
    detail_json JSONB,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ============ 埋点事件（PRD §12） ============
CREATE TABLE track_event (
    id          BIGSERIAL PRIMARY KEY,
    user_id     BIGINT,
    openid      VARCHAR(64),
    event       VARCHAR(64) NOT NULL,
    props_json  JSONB DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_track_event_time ON track_event (event, created_at DESC);
