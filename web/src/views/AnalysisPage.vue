<script setup lang="ts">
import { watch } from 'vue'
import { useRoute } from 'vue-router'
import { useAnalysis } from '@/composables/useAnalysis'
import { useAssets } from '@/composables/useAssets'
import ProductHeader from '@/components/analysis/ProductHeader.vue'
import PriceWaterfall from '@/components/analysis/PriceWaterfall.vue'
import PriceTrendChart from '@/components/analysis/PriceTrendChart.vue'
import AiRecommendation from '@/components/analysis/AiRecommendation.vue'
import RiskTags from '@/components/analysis/RiskTags.vue'
import CrossPlatform from '@/components/analysis/CrossPlatform.vue'
import BuyButton from '@/components/analysis/BuyButton.vue'

const route = useRoute()
const { assets } = useAssets()
const { analysisData, aiData, loading, aiLoading, error, aiError, load } = useAnalysis()

function doLoad() {
  const platform = route.query.platform as string
  const itemId = route.query.item_id as string
  if (platform && itemId) {
    load(platform, itemId, assets)
  }
}

watch(
  () => [route.query.platform, route.query.item_id],
  () => doLoad(),
  { immediate: true }
)

watch(assets, () => doLoad(), { deep: true })

function retryAi() {
  const platform = route.query.platform as string
  const itemId = route.query.item_id as string
  if (platform && itemId) {
    load(platform, itemId, assets)
  }
}
</script>

<template>
  <div class="analysis-page">
    <div v-if="loading && !analysisData" class="page-loading">
      <el-skeleton :rows="8" animated />
    </div>

    <div v-else-if="error && !analysisData" class="page-error">
      <el-result icon="warning" :title="error" sub-title="请检查链接是否正确">
        <template #extra>
          <el-button type="primary" @click="$router.push('/')">返回首页</el-button>
        </template>
      </el-result>
    </div>

    <template v-else-if="analysisData">
      <div class="analysis-content">
        <ProductHeader
          :product="analysisData.productInfo"
          :sku-info="analysisData.skuInfo"
        />

        <PriceWaterfall :price-info="analysisData.priceInfo" />

        <PriceTrendChart
          v-if="analysisData.trendInfo"
          :trend-info="analysisData.trendInfo"
        />

        <AiRecommendation
          :ai-data="aiData"
          :loading="aiLoading"
          :error="aiError"
          @retry="retryAi"
        />

        <RiskTags :risks="analysisData.riskInfo" />

        <CrossPlatform
          :cross-platform="analysisData.crossPlatform"
          :shop-alternative="analysisData.shopAlternative"
        />
      </div>

      <BuyButton
        :cps-link="analysisData.cpsLink"
        :platform="(route.query.platform as string)"
        :item-id="(route.query.item_id as string)"
        :price="analysisData.priceInfo.estimatedFinalPrice"
      />
    </template>
  </div>
</template>

<style scoped>
.analysis-page {
  padding-bottom: 80px;
}

.analysis-content {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.page-loading {
  padding: 40px;
  background: #fff;
  border-radius: 12px;
}

.page-error {
  padding: 40px;
  background: #fff;
  border-radius: 12px;
}
</style>
