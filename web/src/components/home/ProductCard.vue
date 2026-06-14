<script setup lang="ts">
import { useRouter } from 'vue-router'
import { getProductImageUrl } from '@/api/product'
import PriceDisplay from '../common/PriceDisplay.vue'

const props = defineProps<{
  platform: string
  itemId: string
  title: string
  imageUrl?: string | null
  price?: number | null
  shopName?: string | null
  activityTags?: string[]
}>()

const router = useRouter()

function handleClick() {
  router.push({
    path: '/analysis',
    query: { platform: props.platform, item_id: props.itemId },
  })
}

function platformName(p: string): string {
  const map: Record<string, string> = { jd: '京东', pdd: '拼多多' }
  return map[p] || p
}
</script>

<template>
  <div class="product-card" @click="handleClick">
    <div class="card-img-wrap">
      <img
        :src="imageUrl || getProductImageUrl(platform, itemId)"
        class="card-img"
        alt=""
        loading="lazy"
      />
      <el-tag
        class="platform-badge"
        size="small"
        :type="platform === 'jd' ? 'danger' : 'warning'"
      >
        {{ platformName(platform) }}
      </el-tag>
    </div>
    <div class="card-body">
      <p class="card-title">{{ title }}</p>
      <div class="card-footer">
        <PriceDisplay :price="price" size="small" />
        <span v-if="shopName" class="card-shop">{{ shopName }}</span>
      </div>
      <div v-if="activityTags && activityTags.length" class="card-tags">
        <el-tag
          v-for="tag in activityTags.slice(0, 2)"
          :key="tag"
          size="small"
          type="success"
        >
          {{ tag }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<style scoped>
.product-card {
  background: #fff;
  border-radius: 12px;
  overflow: hidden;
  cursor: pointer;
  transition: box-shadow 0.2s, transform 0.2s;
  border: 1px solid #f0f0f0;
}

.product-card:hover {
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  transform: translateY(-2px);
}

.card-img-wrap {
  position: relative;
  aspect-ratio: 1;
  background: #fafafa;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.card-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.platform-badge {
  position: absolute;
  top: 8px;
  left: 8px;
}

.card-body {
  padding: 12px;
}

.card-title {
  font-size: 13px;
  color: #303133;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  margin-bottom: 8px;
  min-height: 36px;
}

.card-footer {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.card-shop {
  font-size: 11px;
  color: #909399;
}

.card-tags {
  display: flex;
  gap: 4px;
  margin-top: 6px;
  flex-wrap: wrap;
}
</style>
