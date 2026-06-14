<script setup lang="ts">
import type { ProductInfo, SkuInfo } from '@/api/types'
import { getProductImageUrl } from '@/api/product'

const props = defineProps<{
  product: ProductInfo
  skuInfo?: SkuInfo | null
}>()

function shopTypeName(t: string): string {
  const map: Record<string, string> = {
    self: '自营',
    flagship: '官方旗舰店',
    thirdparty: '第三方店铺',
  }
  return map[t] || t
}

function shopTypeColor(t: string): string {
  return t === 'self' ? 'danger' : t === 'flagship' ? '' : 'warning'
}
</script>

<template>
  <div class="product-header">
    <div class="product-img-wrap">
      <img
        :src="product.imageUrl || getProductImageUrl(product.platform, product.itemId)"
        class="product-img"
        alt=""
      />
    </div>
    <div class="product-info">
      <h1 class="product-title">{{ product.title }}</h1>
      <p v-if="skuInfo && skuInfo.standardName" class="sku-name">
        标准型号：{{ skuInfo.standardName }}
        <el-tag
          v-if="skuInfo.confidence !== 'high'"
          size="small"
          type="warning"
        >
          {{ skuInfo.confidence === 'medium' ? '型号匹配中等' : '待确认' }}
        </el-tag>
      </p>
      <div class="product-tags">
        <el-tag
          v-if="product.shopType"
          :type="shopTypeColor(product.shopType)"
          size="small"
        >
          {{ shopTypeName(product.shopType) }}
        </el-tag>
        <el-tag v-if="product.shopName" size="small" type="info">
          {{ product.shopName }}
        </el-tag>
        <el-tag
          v-for="tag in product.activityTags"
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
.product-header {
  display: flex;
  gap: 24px;
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.product-img-wrap {
  width: 200px;
  height: 200px;
  flex-shrink: 0;
  background: #fafafa;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
}

.product-img {
  max-width: 100%;
  max-height: 100%;
  object-fit: contain;
}

.product-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.product-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  line-height: 1.5;
}

.sku-name {
  font-size: 14px;
  color: #606266;
  display: flex;
  align-items: center;
  gap: 8px;
}

.product-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
