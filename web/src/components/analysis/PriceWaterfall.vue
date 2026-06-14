<script setup lang="ts">
import type { FinalPriceResult } from '@/api/types'
import PriceDisplay from '../common/PriceDisplay.vue'

defineProps<{
  priceInfo: FinalPriceResult
}>()

function formatAmount(n: number): string {
  return n > 0 ? `-${n}` : `+${Math.abs(n)}`
}
</script>

<template>
  <div class="price-waterfall">
    <h3 class="section-title">价格拆解</h3>

    <div v-if="priceInfo.pricePending" class="price-pending">
      <el-icon class="is-loading"><Loading /></el-icon>
      价格加载中...
    </div>

    <template v-else>
      <div class="waterfall-start">
        <span class="waterfall-label">商品显示价</span>
        <PriceDisplay :price="priceInfo.displayPrice" size="medium" color="#909399" />
      </div>

      <div class="waterfall-steps">
        <div
          v-for="item in priceInfo.included"
          :key="item.name"
          class="waterfall-step"
        >
          <div class="step-bar">
            <span class="step-name">{{ item.name }}</span>
            <span class="step-amount">{{ formatAmount(item.amount) }}</span>
          </div>
        </div>
      </div>

      <div class="waterfall-result">
        <span class="result-label">预估到手价</span>
        <PriceDisplay :price="priceInfo.estimatedFinalPrice" size="large" />
      </div>

      <div v-if="priceInfo.notIncluded.length" class="not-included">
        <span class="not-included-label">未纳入计算：</span>
        <span v-for="item in priceInfo.notIncluded" :key="item" class="not-included-item">
          {{ item }}
        </span>
      </div>

      <div v-if="priceInfo.uncertaintyFlags.length" class="uncertainty">
        <el-tag
          v-for="flag in priceInfo.uncertaintyFlags"
          :key="flag"
          size="small"
          type="info"
        >
          {{ flag }}
        </el-tag>
      </div>

      <p v-if="priceInfo.disclaimer" class="disclaimer">
        {{ priceInfo.disclaimer }}
      </p>
    </template>
  </div>
</template>

<style scoped>
.price-waterfall {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 20px;
}

.price-pending {
  text-align: center;
  color: #909399;
  padding: 24px;
}

.waterfall-start {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  background: #fafafa;
  border-radius: 8px;
  margin-bottom: 8px;
}

.waterfall-label {
  font-size: 14px;
  color: #909399;
}

.waterfall-steps {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 8px;
}

.waterfall-step .step-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 16px;
  background: #f0f9eb;
  border-radius: 6px;
  border-left: 3px solid #67c23a;
}

.step-name {
  font-size: 13px;
  color: #606266;
}

.step-amount {
  font-size: 14px;
  font-weight: 600;
  color: #67c23a;
}

.waterfall-result {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px;
  background: #fef0f0;
  border-radius: 8px;
  border-left: 3px solid #e6393d;
  margin-top: 4px;
}

.result-label {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.not-included {
  margin-top: 12px;
  font-size: 12px;
  color: #909399;
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.not-included-item {
  background: #f5f7fa;
  padding: 2px 8px;
  border-radius: 4px;
}

.uncertainty {
  margin-top: 8px;
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.disclaimer {
  margin-top: 12px;
  font-size: 11px;
  color: #c0c4cc;
}
</style>
