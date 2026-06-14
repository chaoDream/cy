import client from './client'
import type { RankItem } from './types'

export function getPhoneRank(params?: {
  brand?: string
  price_min?: number
  price_max?: number
}): Promise<RankItem[]> {
  return client.get('/api/rank/phone', { params }) as any
}
