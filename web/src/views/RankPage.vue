<script setup lang="ts">
import { ref } from 'vue'
import { getPhoneRank } from '@/api/rank'
import type { RankItem } from '@/api/types'
import RankFilters from '@/components/rank/RankFilters.vue'
import RankTable from '@/components/rank/RankTable.vue'

const items = ref<RankItem[]>([])
const loading = ref(false)

async function handleFilter(params: { brand?: string; price_min?: number; price_max?: number }) {
  loading.value = true
  try {
    items.value = await getPhoneRank(params)
  } catch (e: any) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="rank-page">
    <h2 class="page-title">手机低价榜</h2>
    <p class="page-subtitle">基于京东实时价格，计算真实到手价后排名</p>

    <div class="rank-card">
      <RankFilters @filter="handleFilter" />
      <RankTable :items="items" :loading="loading" style="margin-top: 16px" />
    </div>
  </div>
</template>

<style scoped>
.page-title {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  margin-bottom: 4px;
}

.page-subtitle {
  font-size: 14px;
  color: #909399;
  margin-bottom: 20px;
}

.rank-card {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}
</style>
