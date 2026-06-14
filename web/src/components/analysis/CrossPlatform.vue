<script setup lang="ts">
import { useRouter } from 'vue-router'
import type { CrossPlatformItem, ShopAlternative } from '@/api/types'
import PriceDisplay from '../common/PriceDisplay.vue'

defineProps<{
  crossPlatform: CrossPlatformItem[]
  shopAlternative?: ShopAlternative | null
}>()

const router = useRouter()

function platformName(p: string): string {
  const map: Record<string, string> = { jd: '京东', pdd: '拼多多' }
  return map[p] || p
}

function shopTypeName(t: string): string {
  const map: Record<string, string> = { self: '自营', flagship: '旗舰店', thirdparty: '第三方' }
  return map[t] || t
}

function goAnalysis(platform: string, itemId: string) {
  router.push({ path: '/analysis', query: { platform, item_id: itemId } })
}

function goBuy(link: string | null, platform: string, itemId: string) {
  const url = link || (platform === 'jd' ? `https://item.jd.com/${itemId}.html` : '#')
  window.open(url, '_blank')
}
</script>

<template>
  <div v-if="crossPlatform.length || shopAlternative" class="cross-platform">
    <h3 class="section-title">更多选择</h3>

    <div v-if="shopAlternative" class="alt-card" @click="goAnalysis(shopAlternative.platform, shopAlternative.itemId)">
      <div class="alt-info">
        <el-tag size="small" type="info">{{ shopTypeName(shopAlternative.shopType) }}</el-tag>
        <span class="alt-shop">{{ shopAlternative.shopName }}</span>
      </div>
      <PriceDisplay :price="shopAlternative.estimatedFinalPrice" size="small" />
    </div>

    <div v-for="item in crossPlatform" :key="`${item.platform}-${item.itemId}`" class="alt-card">
      <div class="alt-info">
        <el-tag size="small" :type="item.platform === 'jd' ? 'danger' : 'warning'">
          {{ platformName(item.platform) }}
        </el-tag>
        <el-tag size="small" type="info">{{ shopTypeName(item.shopType) }}</el-tag>
        <span class="alt-shop">{{ item.shopName }}</span>
      </div>
      <div class="alt-actions">
        <PriceDisplay :price="item.estimatedFinalPrice" size="small" />
        <el-button size="small" type="primary" plain @click="goBuy(item.cpsLink, item.platform, item.itemId)">
          去购买
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.cross-platform {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}

.alt-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px;
  border: 1px solid #f0f0f0;
  border-radius: 8px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: background 0.15s;
}

.alt-card:hover {
  background: #fafafa;
}

.alt-info {
  display: flex;
  align-items: center;
  gap: 8px;
}

.alt-shop {
  font-size: 13px;
  color: #606266;
}

.alt-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
</style>
