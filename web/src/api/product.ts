import client from './client'
import type {
  ParseResult,
  AnalysisCoreResult,
  AiResult,
  SearchItem,
  RecommendItem,
  UserAssets,
} from './types'

export function parseLink(linkText: string): Promise<ParseResult> {
  return client.post('/api/link/parse', { linkText }) as any
}

export function getAnalysis(
  platform: string,
  itemId: string,
  assets?: UserAssets
): Promise<AnalysisCoreResult> {
  const params: Record<string, string> = { platform, item_id: itemId }
  if (assets) {
    params.assets = JSON.stringify(assets)
  }
  return client.get('/api/product/analysis', { params }) as any
}

export function getAiRecommendation(
  platform: string,
  itemId: string,
  assets?: UserAssets,
  forceRule?: boolean
): Promise<AiResult> {
  const params: Record<string, string | boolean> = { platform, item_id: itemId }
  if (assets) {
    params.assets = JSON.stringify(assets)
  }
  if (forceRule) {
    params.forceRule = true
  }
  return client.get('/api/product/ai-recommendation', { params }) as any
}

export function searchProducts(keyword: string): Promise<SearchItem[]> {
  return client.get('/api/product/search', { params: { keyword } }) as any
}

export function getRecommend(): Promise<RecommendItem[]> {
  return client.get('/api/product/recommend') as any
}

export function getProductImageUrl(platform: string, itemId: string): string {
  return `/api/product/image?platform=${platform}&item_id=${itemId}`
}
