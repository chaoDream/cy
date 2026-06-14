<script setup lang="ts">
import { ref } from 'vue'

const dismissed = ref(false)
</script>

<template>
  <div v-if="!dismissed" class="banner">
    <span class="banner-text">📱 微信小程序即将上线，支持盯价提醒、降价推送</span>
    <el-button type="primary" size="small" text @click="dismissed = true">知道了</el-button>
  </div>
</template>

<style scoped>
.banner {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: #fff;
  padding: 12px 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  z-index: 200;
  font-size: 14px;
}

.banner :deep(.el-button) {
  color: #fff;
}
</style>
