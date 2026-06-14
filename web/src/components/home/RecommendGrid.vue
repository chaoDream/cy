<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { getRecommend } from '@/api/product'
import type { RecommendItem } from '@/api/types'
import ProductCard from './ProductCard.vue'

const items = ref<RecommendItem[]>([])
const loading = ref(true)

onMounted(async () => {
  try {
    items.value = await getRecommend()
  } catch {}
  loading.value = false
})
</script>

<template>
  <div class="recommend-section">
    <h3 class="section-title">当前好价</h3>
    <div v-if="loading" class="loading-wrap">
      <el-skeleton :rows="3" animated />
    </div>
    <div v-else-if="items.length" class="product-grid">
      <ProductCard
        v-for="item in items"
        :key="`${item.platform}-${item.platformItemId}`"
        :platform="item.platform"
        :item-id="item.platformItemId"
        :title="item.title"
        :image-url="item.imageUrl"
        :price="item.bestFinalPrice"
        :shop-name="item.shopName"
        :activity-tags="item.activityTags"
      />
    </div>
    <el-empty v-else description="暂无推荐" />
  </div>
</template>

<style scoped>
.recommend-section {
  margin-top: 32px;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
}

.product-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 16px;
}

.loading-wrap {
  padding: 20px;
}
</style>
