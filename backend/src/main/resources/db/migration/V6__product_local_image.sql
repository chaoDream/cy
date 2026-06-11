-- 商品主图本地落盘路径（外链仍保留在 image_url 供溯源与重拉）
ALTER TABLE product_raw
    ADD COLUMN IF NOT EXISTS local_image_path VARCHAR(512),
    ADD COLUMN IF NOT EXISTS image_stored_at TIMESTAMPTZ;
