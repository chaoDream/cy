<script setup lang="ts">
import PriceDisplay from '../common/PriceDisplay.vue'

const props = defineProps<{
  cpsLink: string | null
  platform: string
  itemId: string
  price: number | null | undefined
}>()

function handleBuy() {
  const url =
    props.cpsLink ||
    (props.platform === 'jd'
      ? `https://item.jd.com/${props.itemId}.html`
      : '#')
  window.open(url, '_blank')
}

function handleShare() {
  const url = window.location.href
  navigator.clipboard.writeText(url).then(() => {
    ElMessage.success('链接已复制到剪贴板')
  })
}
</script>

<template>
  <div class="buy-bar">
    <div class="buy-bar-inner">
      <div class="buy-price">
        <span class="buy-price-label">到手价</span>
        <PriceDisplay :price="price" size="large" />
      </div>
      <div class="buy-actions">
        <el-button round @click="handleShare">分享</el-button>
        <el-button type="danger" round size="large" @click="handleBuy">
          去京东购买
        </el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.buy-bar {
  position: sticky;
  bottom: 0;
  background: #fff;
  border-top: 1px solid #e4e7ed;
  padding: 12px 0;
  z-index: 50;
  margin: 0 -20px;
}

.buy-bar-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.buy-price {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.buy-price-label {
  font-size: 14px;
  color: #909399;
}

.buy-actions {
  display: flex;
  gap: 12px;
}
</style>
