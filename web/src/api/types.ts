export interface ProductInfo {
  platform: string
  itemId: string
  title: string
  imageUrl: string | null
  shopName: string | null
  shopType: string
  rawPrice: number | null
  activityTags: string[]
  sourceUrl: string | null
  rawProductId?: number
}

export interface DiscountItem {
  name: string
  amount: number
  included: boolean
}

export interface FinalPriceResult {
  pricePending: boolean
  displayPrice: number | null
  estimatedFinalPrice: number | null
  couponAmount: number
  subsidyAmount: number
  freight: number
  included: DiscountItem[]
  notIncluded: string[]
  uncertaintyFlags: string[]
  govSubsidyEligible: boolean
  disclaimer: string | null
}

export interface TrendPoint {
  date: string
  price: number
}

export interface PriceTrend {
  points: TrendPoint[]
  low30: number | null
  low90: number | null
  nearLow: boolean
  fakeDiscount: boolean
  historyInsufficient: boolean
  note: string | null
}

export interface SkuInfo {
  standardName: string
  confidence: string
  needConfirm: boolean
}

export interface CrossPlatformItem {
  platform: string
  itemId: string
  shopName: string
  shopType: string
  estimatedFinalPrice: number
  cpsLink: string | null
}

export interface ShopAlternative {
  platform: string
  itemId: string
  title: string
  shopName: string
  shopType: string
  estimatedFinalPrice: number
}

export interface AnalysisCoreResult {
  productInfo: ProductInfo
  skuInfo: SkuInfo | null
  priceInfo: FinalPriceResult
  trendInfo: PriceTrend | null
  riskInfo: string[]
  crossPlatform: CrossPlatformItem[]
  shopAlternative: ShopAlternative | null
  cpsLink: string | null
}

export interface AiResult {
  conclusion: string
  conclusionLabel: string
  reasons: string[]
  riskHint: string | null
  confidence: string | null
}

export interface ParseResult {
  platform: string
  itemId: string
  rawProductId: number
  productInfo: ProductInfo
  parseStatus: string
}

export interface SearchItem {
  platform: string
  itemId: string
  rawProductId: number
  title: string
  imageUrl: string | null
  rawPrice: number | null
}

export interface RecommendItem {
  platform: string
  platformItemId: string
  title: string
  imageUrl: string | null
  bestFinalPrice: number | null
  shopName: string | null
  activityTags: string[]
}

export interface RankItem {
  skuId: number
  standardName: string
  brand: string
  bestFinalPrice: number
  platform: string
  rawProductId: number
  platformItemId: string
  imageUrl: string | null
  title: string
  riskTags: string[]
  capturedAt: string
}

export interface UserAssets {
  vip88: boolean
  jdPlus: boolean
  pddMonthly: boolean
  govSubsidyRegion: string | null
}
