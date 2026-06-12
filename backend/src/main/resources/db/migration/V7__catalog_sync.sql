-- 平台同步的品牌 / 型号目录（供 SkuService 识别，定时从京东/拼多多充盈）

CREATE TABLE catalog_brand (
    id              BIGSERIAL PRIMARY KEY,
    canonical_name  VARCHAR(64) NOT NULL UNIQUE,
    aliases         JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_platform VARCHAR(16) NOT NULL DEFAULT 'system',
    sample_count    INT NOT NULL DEFAULT 0,
    last_seen_at    TIMESTAMPTZ,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE catalog_model (
    id              BIGSERIAL PRIMARY KEY,
    brand           VARCHAR(64) NOT NULL,
    model           VARCHAR(128) NOT NULL,
    aliases         JSONB NOT NULL DEFAULT '[]'::jsonb,
    source_platform VARCHAR(16) NOT NULL DEFAULT 'system',
    example_title   VARCHAR(512),
    sample_count    INT NOT NULL DEFAULT 0,
    last_seen_at    TIMESTAMPTZ,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (brand, model)
);
CREATE INDEX idx_catalog_model_brand ON catalog_model (brand);

-- 内置种子品牌（与 SkuService 原硬编码对齐，后续由定时任务从平台扩充别名）
INSERT INTO catalog_brand (canonical_name, aliases, source_platform, status) VALUES
  ('Apple', '["iphone","apple","苹果"]', 'system', 'active'),
  ('华为', '["华为","mate","pura"]', 'system', 'active'),
  ('小米', '["小米","xiaomi"]', 'system', 'active'),
  ('Redmi', '["redmi"]', 'system', 'active'),
  ('vivo', '["vivo"]', 'system', 'active'),
  ('OPPO', '["oppo"]', 'system', 'active'),
  ('一加', '["一加","oneplus"]', 'system', 'active'),
  ('荣耀', '["荣耀","honor"]', 'system', 'active'),
  ('三星', '["三星","samsung","galaxy"]', 'system', 'active');
