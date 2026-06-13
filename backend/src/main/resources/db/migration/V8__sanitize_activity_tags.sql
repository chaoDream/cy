-- 一次性清洗 product_raw 中的数据源占位标签（京东联盟/维易/多多进宝等）
UPDATE product_raw
SET activity_tags = COALESCE(
    (
        SELECT jsonb_agg(to_jsonb(trimmed))
        FROM (
            SELECT trim(elem) AS trimmed
            FROM jsonb_array_elements_text(COALESCE(activity_tags, '[]'::jsonb)) AS elem
            WHERE trim(elem) <> ''
              AND trim(elem) NOT IN ('京东联盟', '维易', '维易·京东联盟', '多多进宝')
        ) AS cleaned
    ),
    '[]'::jsonb
),
updated_at = NOW()
WHERE activity_tags IS NOT NULL
  AND activity_tags != '[]'::jsonb;
