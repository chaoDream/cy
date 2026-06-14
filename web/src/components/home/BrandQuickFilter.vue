<script setup lang="ts">
import { useRouter } from 'vue-router'

const router = useRouter()

const brands = [
  { label: 'Apple', value: 'Apple' },
  { label: '华为', value: '华为' },
  { label: '小米', value: '小米' },
  { label: 'vivo', value: 'vivo' },
  { label: 'OPPO', value: 'OPPO' },
  { label: '荣耀', value: '荣耀' },
  { label: '三星', value: '三星' },
  { label: '一加', value: '一加' },
]

function goRank(brand: string) {
  router.push({ path: '/rank', query: { brand } })
}
</script>

<template>
  <div class="brand-filter">
    <h3 class="section-title">按品牌查看低价榜</h3>
    <div class="brand-pills">
      <el-button
        v-for="b in brands"
        :key="b.value"
        round
        @click="goRank(b.value)"
      >
        {{ b.label }}
      </el-button>
      <el-button round type="primary" plain @click="$router.push('/rank')">
        全部品牌
      </el-button>
    </div>
  </div>
</template>

<style scoped>
.brand-filter {
  margin: 32px 0;
}

.section-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
}

.brand-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
</style>
