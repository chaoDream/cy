<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { RankItem } from '@/api/types'
import { getProductImageUrl } from '@/api/product'

defineProps<{
  items: RankItem[]
  loading: boolean
}>()

const router = useRouter()

function goAnalysis(item: RankItem) {
  router.push({
    path: '/analysis',
    query: { platform: item.platform, item_id: item.platformItemId },
  })
}

function platformName(p: string): string {
  const map: Record<string, string> = { jd: '京东', pdd: '拼多多' }
  return map[p] || p
}
</script>

<template>
  <el-table :data="items" v-loading="loading" stripe style="width: 100%">
    <el-table-column label="#" width="50" type="index" :index="(i: number) => i + 1" />
    <el-table-column label="商品" min-width="300">
      <template #default="{ row }">
        <div class="product-cell" @click="goAnalysis(row)">
          <img
            :src="row.imageUrl || getProductImageUrl(row.platform, row.platformItemId)"
            class="rank-img"
            alt=""
            loading="lazy"
          />
          <div class="product-text">
            <span class="product-name">{{ row.standardName || row.title }}</span>
            <span class="product-brand">{{ row.brand }}</span>
          </div>
        </div>
      </template>
    </el-table-column>
    <el-table-column label="最低到手价" width="140" sortable :sort-method="(a: RankItem, b: RankItem) => a.bestFinalPrice - b.bestFinalPrice">
      <template #default="{ row }">
        <span class="rank-price">¥{{ row.bestFinalPrice }}</span>
      </template>
    </el-table-column>
    <el-table-column label="平台" width="100">
      <template #default="{ row }">
        <el-tag size="small" :type="row.platform === 'jd' ? 'danger' : 'warning'">
          {{ platformName(row.platform) }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="风险" width="200">
      <template #default="{ row }">
        <el-tag
          v-for="tag in row.riskTags"
          :key="tag"
          size="small"
          type="warning"
          style="margin: 2px"
        >
          {{ tag }}
        </el-tag>
      </template>
    </el-table-column>
    <el-table-column label="操作" width="100" fixed="right">
      <template #default="{ row }">
        <el-button type="primary" link @click="goAnalysis(row)">查看分析</el-button>
      </template>
    </el-table-column>
  </el-table>
</template>

<style scoped>
.product-cell {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
}

.rank-img {
  width: 48px;
  height: 48px;
  object-fit: contain;
  border-radius: 4px;
  background: #fafafa;
}

.product-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.product-name {
  font-size: 14px;
  color: #303133;
  font-weight: 500;
}

.product-brand {
  font-size: 12px;
  color: #909399;
}

.rank-price {
  font-size: 16px;
  font-weight: 700;
  color: #e6393d;
}
</style>
