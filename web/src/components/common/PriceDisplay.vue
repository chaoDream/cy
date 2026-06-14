<script setup lang="ts">
defineProps<{
  price: number | null | undefined
  size?: 'small' | 'medium' | 'large'
  color?: string
}>()

function format(v: number): string {
  const s = v.toFixed(2)
  return s.endsWith('.00') ? s.slice(0, -3) : s.endsWith('0') ? s.slice(0, -1) : s
}
</script>

<template>
  <span
    class="price-display"
    :class="[size || 'medium']"
    :style="{ color: color || '#e6393d' }"
  >
    <span class="price-symbol">¥</span>
    <span class="price-value">{{ price != null ? format(price) : '--' }}</span>
  </span>
</template>

<style scoped>
.price-display {
  font-weight: 700;
  white-space: nowrap;
}

.price-symbol {
  font-size: 0.7em;
  margin-right: 1px;
}

.small { font-size: 14px; }
.medium { font-size: 20px; }
.large { font-size: 32px; }
</style>
