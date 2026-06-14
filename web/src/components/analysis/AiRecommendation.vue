<script setup lang="ts">
import type { AiResult } from '@/api/types'

defineProps<{
  aiData: AiResult | null
  loading: boolean
  error: string | null
}>()

const emit = defineEmits<{
  retry: []
}>()

function conclusionStyle(c: string): Record<string, string> {
  const map: Record<string, { bg: string; border: string; color: string }> = {
    BUY: { bg: '#f0f9eb', border: '#67c23a', color: '#67c23a' },
    WAIT: { bg: '#fdf6ec', border: '#e6a23c', color: '#e6a23c' },
    CAUTION: { bg: '#fef0f0', border: '#f56c6c', color: '#f56c6c' },
    AVOID: { bg: '#fef0f0', border: '#e6393d', color: '#e6393d' },
  }
  const s = map[c] || map.WAIT
  return { background: s.bg, borderLeftColor: s.border, '--conclusion-color': s.color }
}
</script>

<template>
  <div class="ai-recommendation">
    <h3 class="section-title">AI 购买建议</h3>

    <div v-if="loading" class="ai-loading">
      <el-skeleton :rows="3" animated />
      <p class="ai-loading-text">AI 正在分析中...</p>
    </div>

    <div v-else-if="error" class="ai-error">
      <p>AI 分析暂时不可用</p>
      <el-button size="small" @click="emit('retry')">重试</el-button>
    </div>

    <div v-else-if="aiData" class="ai-card" :style="conclusionStyle(aiData.conclusion)">
      <div class="ai-header">
        <span class="conclusion-label">{{ aiData.conclusionLabel }}</span>
        <el-tag v-if="aiData.confidence" size="small" type="info">
          置信度：{{ aiData.confidence }}
        </el-tag>
      </div>
      <ul class="ai-reasons">
        <li v-for="(reason, i) in aiData.reasons" :key="i">{{ reason }}</li>
      </ul>
      <p v-if="aiData.riskHint" class="ai-risk-hint">
        ⚠️ {{ aiData.riskHint }}
      </p>
    </div>
  </div>
</template>

<style scoped>
.ai-recommendation {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
}

.ai-loading {
  padding: 12px 0;
}

.ai-loading-text {
  text-align: center;
  color: #909399;
  font-size: 13px;
  margin-top: 12px;
}

.ai-error {
  text-align: center;
  color: #909399;
  padding: 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
}

.ai-card {
  border-left: 4px solid;
  border-radius: 8px;
  padding: 16px 20px;
}

.ai-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.conclusion-label {
  font-size: 20px;
  font-weight: 700;
  color: var(--conclusion-color);
}

.ai-reasons {
  list-style: none;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.ai-reasons li {
  font-size: 14px;
  color: #606266;
  line-height: 1.5;
  padding-left: 16px;
  position: relative;
}

.ai-reasons li::before {
  content: '•';
  position: absolute;
  left: 0;
  color: var(--conclusion-color);
  font-weight: bold;
}

.ai-risk-hint {
  margin-top: 12px;
  font-size: 13px;
  color: #e6a23c;
  background: #fdf6ec;
  padding: 8px 12px;
  border-radius: 6px;
}
</style>
