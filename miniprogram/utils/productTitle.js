/**
 * 从联盟/电商长标题提取「品牌 + 型号 + 容量」，供最近查询等紧凑展示。
 */
const BRAND_ENTRIES = [
  { brand: 'Apple', aliases: ['iPhone', 'Apple', '苹果'] },
  { brand: '华为', aliases: ['华为', 'HUAWEI', 'Huawei'] },
  { brand: '小米', aliases: ['小米', 'Xiaomi', 'Redmi', '红米'] },
  { brand: 'vivo', aliases: ['vivo', 'iQOO', 'IQOO'] },
  { brand: 'OPPO', aliases: ['OPPO', 'oppo'] },
  { brand: '一加', aliases: ['一加', 'OnePlus', 'oneplus'] },
  { brand: '荣耀', aliases: ['荣耀', 'Honor', 'HONOR'] },
  { brand: '三星', aliases: ['三星', 'Samsung', 'Galaxy'] },
];

const MODEL_REGEXES = [
  /iPhone\s?\d+\s?(?:Pro\s?Max|Pro|Plus|Max|e)?/i,
  /Mate\s?\d+\s?(?:Pro\+?|RS|保时捷)?/i,
  /Pura\s?\d+\s?(?:Pro\+?|Ultra)?/i,
  /nova\s?\d+\s?(?:Pro\+?|Ultra)?/i,
  /(?:小米|Xiaomi)\s?\d+\s?(?:Pro\s?Max|Pro|Ultra|Max)?/i,
  /(?:Redmi|红米)\s?[\w\d]+\s?(?:Pro\+?|Pro|Ultra|至尊版|Turbo\s?\d+)?/i,
  /(?:一加|OnePlus)\s?(?:Ace\s?\d+\s?(?:Pro\+?|Pro|Max)?|\d+\s?(?:Pro\s?Max|Pro|R)?)/i,
  /(?:vivo\s?)?X\d{3}\s?(?:Pro\+?|Ultra)?/i,
  /(?:vivo\s?)?S\d+\s?(?:Pro\+?|Ultra)?/i,
  /(?:vivo\s?)?Y\d+\w*\s?(?:Pro\+?)?/i,
  /iQOO\s?(?:Neo\s?\d+\s?Pro\+?|Neo\s?\d+\s?Pro|\d+\s?(?:Pro\+?|Pro)?)/i,
  /Find\s?X\d+\s?(?:Pro\+?|Ultra)?/i,
  /Reno\s?\d+\s?(?:Pro\+?|Ultra)?/i,
  /(?:OPPO\s?)?A\d+\s?Pro/i,
  /Magic\s?\d+\s?(?:Pro\+?|Ultimate)?/i,
  /Galaxy\s?S\d+\s?(?:Ultra\+?|Ultra|\+)?/i,
  /Galaxy\s?Z\s?(?:Fold|Flip)\s?\d+/i,
];

const STORAGE_COMBINED_RE = /(\d+\s*GB\s*\+\s*\d+\s*GB)/i;
const STORAGE_SINGLE_RE = /(\d+\s*(?:GB|TB))\b/i;

const NOISE_RE = [
  /【[^】]*】/g,
  /\[[^\]]*\]/g,
  /（[^）]*）/g,
  /\([^)]*\)/g,
];

function normalizeTitle(title) {
  return title
    .replace(/\s+/g, ' ')
    .replace(/苹果\s*(\d+)/gi, 'iPhone $1 ')
    .replace(/iphone/gi, 'iPhone')
    .replace(/mate(\d)/gi, 'Mate $1')
    .replace(/pura(\d)/gi, 'Pura $1')
    .trim();
}

function stripNoise(title) {
  let s = title;
  NOISE_RE.forEach((re) => { s = s.replace(re, ' '); });
  return s.replace(/\s+/g, ' ').trim();
}

function findBrand(title) {
  for (const entry of BRAND_ENTRIES) {
    for (const alias of entry.aliases) {
      const idx = title.indexOf(alias);
      if (idx >= 0) {
        return { brand: entry.brand, word: title.substring(idx, idx + alias.length), index: idx };
      }
      const idxIgnore = title.toLowerCase().indexOf(alias.toLowerCase());
      if (idxIgnore >= 0) {
        return {
          brand: entry.brand,
          word: title.substring(idxIgnore, idxIgnore + alias.length),
          index: idxIgnore,
        };
      }
    }
  }
  return null;
}

function extractModel(title, brandInfo) {
  for (const re of MODEL_REGEXES) {
    const m = title.match(re);
    if (m) return normalizeModelCase(m[0].replace(/\s+/g, ' ').trim());
  }
  if (!brandInfo) return '';
  const after = title.substring(brandInfo.index + brandInfo.word.length).trim();
  if (!after) return '';
  const tokens = after.split(/[\s+/,，、]+/).filter(Boolean);
  const modelTokens = [];
  for (const tok of tokens) {
    if (STORAGE_COMBINED_RE.test(tok) || STORAGE_SINGLE_RE.test(tok) || /^\d+[gGtT]$/.test(tok)) break;
    if (tok.length === 1 && !/\d/.test(tok)) continue;
    if (/^5g$/i.test(tok)) continue;
    modelTokens.push(tok);
    if (modelTokens.length >= 5) break;
  }
  return modelTokens.join(' ').trim();
}

function extractStorage(title) {
  const combined = title.match(STORAGE_COMBINED_RE);
  if (combined) return combined[1].replace(/\s+/g, '');
  const single = title.match(STORAGE_SINGLE_RE);
  if (single) return single[1].replace(/\s+/g, '');
  return '';
}

function normalizeModelCase(model) {
  if (!model) return '';
  return model
    .replace(/\bpro max\b/gi, 'Pro Max')
    .replace(/\bultra\b/gi, 'Ultra')
    .replace(/\bplus\b/gi, 'Plus')
    .replace(/\bpro\b/gi, 'Pro')
    .replace(/\bmax\b/gi, 'Max')
    .replace(/\s+/g, ' ')
    .trim();
}

/** 紧凑型号名：如「iPhone 16 Pro 256GB」「华为 Mate 70 Pro 512GB」 */
function compactModelName(title) {
  if (!title || !String(title).trim()) return '未知商品';
  const cleaned = stripNoise(normalizeTitle(String(title)));
  const brandInfo = findBrand(cleaned);
  let model = normalizeModelCase(extractModel(cleaned, brandInfo));
  const storage = extractStorage(cleaned);

  let brandLabel = '';
  if (brandInfo) {
    if (brandInfo.brand === 'Apple' && /^iPhone/i.test(model)) {
      brandLabel = '';
    } else if (brandInfo.brand === '小米' && /^(Redmi|红米|小米|Xiaomi)/i.test(model)) {
      brandLabel = '';
    } else if (brandInfo.brand === 'vivo' && /^(iQOO|vivo|X\d|S\d|Y\d)/i.test(model)) {
      brandLabel = '';
    } else if (brandInfo.brand === '华为' && /^华为/.test(model)) {
      brandLabel = '';
    } else if (brandInfo.brand === 'OPPO' && /^(Find|Reno|A\d)/i.test(model)) {
      brandLabel = 'OPPO';
    } else if (brandInfo.brand === '华为' && /^(Mate|Pura|nova)/i.test(model)) {
      brandLabel = '华为';
    } else {
      brandLabel = brandInfo.brand;
    }
  }

  if (brandLabel && model.toLowerCase().startsWith(brandLabel.toLowerCase())) {
    brandLabel = '';
  }

  const parts = [brandLabel, model, storage].filter(Boolean);
  if (parts.length) return parts.join(' ');

  // 兜底：取清理后标题前 24 字，去掉常见营销词
  let fallback = cleaned.slice(0, 32);
  ['国行', '5G', '全新', '官方', '自营', '旗舰店'].forEach((w) => {
    fallback = fallback.replace(new RegExp(w, 'g'), '');
  });
  fallback = fallback.replace(/\s+/g, ' ').trim();
  return fallback || cleaned.slice(0, 20) || '未知商品';
}

/** 型号名归一化，供历史去重（忽略 OPPO/OnePlus/空格等差异） */
function normalizeModelKey(name) {
  let s = String(name || '').toLowerCase();
  s = s.replace(/oneplus/g, '一加').replace(/oppo/g, ' ');
  s = s.replace(/\s+/g, '');
  s = s.replace(/(一加)+/g, '一加');
  s = s.replace(/(小米)+/g, '小米');
  s = s.replace(/(华为)+/g, '华为');
  return s.trim();
}

/** 最近查询去重键：同平台 + 同型号视为同一条 */
function recentDedupKey(entry) {
  const platform = String(entry.platform || '').toLowerCase();
  const model = entry.modelName || compactModelName(entry.title || '');
  const normalized = normalizeModelKey(model);
  if (normalized && normalized !== '未知商品') {
    return `${platform}|${normalized}`;
  }
  return `${platform}|id:${String(entry.itemId ?? '')}`;
}

/** 保留最新一条，去掉同键重复项 */
function dedupeRecentList(list) {
  const seen = new Set();
  const out = [];
  for (const r of list || []) {
    const key = recentDedupKey(r);
    if (seen.has(key)) continue;
    seen.add(key);
    out.push(r);
  }
  return out;
}

module.exports = { compactModelName, recentDedupKey, dedupeRecentList };
