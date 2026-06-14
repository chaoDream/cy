<script setup lang="ts">
defineProps<{
  risks: string[]
}>()
</script>

<template>
  <div v-if="risks.length" class="risk-tags">
    <h3 class="section-title">风险提示</h3>
    <div class="tags-wrap">
      <el-tag
        v-for="risk in risks"
        :key="risk"
        type="warning"
        effect="plain"
      >
        ⚠️ {{ risk }}
      </el-tag>
    </div>
  </div>
</template>

<style scoped>
.risk-tags {
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

.tags-wrap {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
