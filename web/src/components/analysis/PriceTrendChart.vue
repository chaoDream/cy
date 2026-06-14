<script setup lang="ts">
import { computed } from 'vue'
import VChart from 'vue-echarts'
import { use } from 'echarts/core'
import { LineChart } from 'echarts/charts'
import {
  TitleComponent,
  TooltipComponent,
  GridComponent,
  MarkLineComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
import type { PriceTrend } from '@/api/types'

use([LineChart, TitleComponent, TooltipComponent, GridComponent, MarkLineComponent, CanvasRenderer])

const props = defineProps<{
  trendInfo: PriceTrend
}>()

const option = computed(() => {
  const dates = props.trendInfo.points.map((p) => p.date)
  const prices = props.trendInfo.points.map((p) => p.price)

  const markLines: any[] = []
  if (props.trendInfo.low30 != null) {
    markLines.push({
      yAxis: props.trendInfo.low30,
      label: { formatter: `30天最低 ¥${props.trendInfo.low30}`, position: 'insideEndTop' },
      lineStyle: { color: '#67c23a', type: 'dashed' },
    })
  }
  if (props.trendInfo.low90 != null && props.trendInfo.low90 !== props.trendInfo.low30) {
    markLines.push({
      yAxis: props.trendInfo.low90,
      label: { formatter: `90天最低 ¥${props.trendInfo.low90}`, position: 'insideEndTop' },
      lineStyle: { color: '#409eff', type: 'dashed' },
    })
  }

  return {
    tooltip: {
      trigger: 'axis',
      formatter: (params: any) => {
        const p = params[0]
        return `${p.axisValue}<br/>价格：<b>¥${p.value}</b>`
      },
    },
    grid: { left: 60, right: 20, top: 20, bottom: 30 },
    xAxis: {
      type: 'category',
      data: dates,
      axisLabel: { fontSize: 11, color: '#909399' },
    },
    yAxis: {
      type: 'value',
      axisLabel: { formatter: '¥{value}', fontSize: 11, color: '#909399' },
      splitLine: { lineStyle: { type: 'dashed', color: '#f0f0f0' } },
    },
    series: [
      {
        type: 'line',
        data: prices,
        smooth: true,
        symbol: 'circle',
        symbolSize: 4,
        lineStyle: { color: '#e6393d', width: 2 },
        itemStyle: { color: '#e6393d' },
        areaStyle: {
          color: {
            type: 'linear',
            x: 0, y: 0, x2: 0, y2: 1,
            colorStops: [
              { offset: 0, color: 'rgba(230,57,61,0.15)' },
              { offset: 1, color: 'rgba(230,57,61,0)' },
            ],
          },
        },
        markLine: markLines.length
          ? { silent: true, symbol: 'none', data: markLines }
          : undefined,
      },
    ],
  }
})
</script>

<template>
  <div class="price-trend">
    <div class="trend-header">
      <h3 class="section-title">价格走势</h3>
      <div class="trend-badges">
        <el-tag v-if="trendInfo.nearLow" type="success" size="small">接近历史低价</el-tag>
        <el-tag v-if="trendInfo.fakeDiscount" type="danger" size="small">疑似先涨后降</el-tag>
      </div>
    </div>

    <div v-if="trendInfo.historyInsufficient" class="insufficient">
      <el-icon><InfoFilled /></el-icon>
      历史数据积累中，持续关注后可查看完整走势
    </div>

    <v-chart
      v-else
      :option="option"
      style="width: 100%; height: 300px"
      autoresize
    />

    <p v-if="trendInfo.note" class="trend-note">{{ trendInfo.note }}</p>
  </div>
</template>

<style scoped>
.price-trend {
  background: #fff;
  border-radius: 12px;
  padding: 24px;
}

.trend-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.trend-badges {
  display: flex;
  gap: 6px;
}

.insufficient {
  display: flex;
  align-items: center;
  gap: 6px;
  color: #909399;
  font-size: 14px;
  padding: 40px 0;
  justify-content: center;
}

.trend-note {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}
</style>
