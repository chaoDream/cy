-- 种子采价：商品名称搜索后绑定的平台商品 ID（避免每天搜索结果漂移）
CREATE TABLE price_seed_binding (
    id                BIGSERIAL PRIMARY KEY,
    seed_name         VARCHAR(256) NOT NULL,
    platform          VARCHAR(8) NOT NULL,
    platform_item_id  VARCHAR(128) NOT NULL,
    raw_product_id    BIGINT REFERENCES product_raw (id),
    resolved_title    VARCHAR(512),
    last_polled_at    TIMESTAMPTZ,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (seed_name, platform)
);

CREATE INDEX idx_price_seed_binding_platform ON price_seed_binding (platform);
