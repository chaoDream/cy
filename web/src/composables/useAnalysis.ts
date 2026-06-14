import { ref } from 'vue'
import { getAnalysis, getAiRecommendation } from '@/api/product'
import type { AnalysisCoreResult, AiResult, UserAssets } from '@/api/types'

export function useAnalysis() {
  const analysisData = ref<AnalysisCoreResult | null>(null)
  const aiData = ref<AiResult | null>(null)
  const loading = ref(false)
  const aiLoading = ref(false)
  const error = ref<string | null>(null)
  const aiError = ref<string | null>(null)

  async function load(platform: string, itemId: string, assets?: UserAssets) {
    loading.value = true
    aiLoading.value = true
    error.value = null
    aiError.value = null
    analysisData.value = null
    aiData.value = null

    const analysisPromise = getAnalysis(platform, itemId, assets)
      .then((data) => {
        analysisData.value = data
      })
      .catch((e) => {
        error.value = e.message
      })
      .finally(() => {
        loading.value = false
      })

    const aiPromise = getAiRecommendation(platform, itemId, assets)
      .catch(() => getAiRecommendation(platform, itemId, assets, true))
      .then((data) => {
        aiData.value = data
      })
      .catch((e) => {
        aiError.value = e.message
      })
      .finally(() => {
        aiLoading.value = false
      })

    await Promise.all([analysisPromise, aiPromise])
  }

  return { analysisData, aiData, loading, aiLoading, error, aiError, load }
}
