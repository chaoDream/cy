<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'

const emit = defineEmits<{
  filter: [params: { brand?: string; price_min?: number; price_max?: number }]
}>()

const route = useRoute()
const brand = ref<string>('')
const priceMin = ref<number | undefined>()
const priceMax = ref<number | undefined>()

const brands = [
  { label: '全部品牌', value: '' },
  { label: 'Apple', value: 'Apple' },
  { label: '华为', value: '华为' },
  { label: '小米', value: '小米' },
  { label: 'vivo', value: 'vivo' },
  { label: 'OPPO', value: 'OPPO' },
  { label: '荣耀', value: '荣耀' },
  { label: '三星', value: '三星' },
  { label: '一加', value: '一加' },
]

onMounted(() => {
  if (route.query.brand) {
    brand.value = route.query.brand as string
  }
  doFilter()
})

function doFilter() {
  emit('filter', {
    brand: brand.value || undefined,
    price_min: priceMin.value,
    price_max: priceMax.value,
  })
}
</script>

<template>
  <div class="rank-filters">
    <el-select v-model="brand" placeholder="选择品牌" @change="doFilter" style="width: 150px">
      <el-option
        v-for="b in brands"
        :key="b.value"
        :label="b.label"
        :value="b.value"
      />
    </el-select>
    <el-input-number
      v-model="priceMin"
      :min="0"
      :max="99999"
      placeholder="最低价"
      controls-position="right"
      style="width: 140px"
      @change="doFilter"
    />
    <span class="range-sep">—</span>
    <el-input-number
      v-model="priceMax"
      :min="0"
      :max="99999"
      placeholder="最高价"
      controls-position="right"
      style="width: 140px"
      @change="doFilter"
    />
  </div>
</template>

<style scoped>
.rank-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.range-sep {
  color: #c0c4cc;
}
</style>
