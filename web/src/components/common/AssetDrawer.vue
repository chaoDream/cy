<script setup lang="ts">
import { useAssets } from '@/composables/useAssets'

const visible = defineModel<boolean>({ default: false })
const { assets } = useAssets()

const regions = [
  '北京', '上海', '广东', '浙江', '江苏', '四川', '湖北', '湖南', '山东', '河南',
]
</script>

<template>
  <el-drawer v-model="visible" title="我的权益" direction="rtl" size="320px">
    <div class="asset-form">
      <div class="asset-item">
        <span class="asset-label">88VIP 会员</span>
        <el-switch v-model="assets.vip88" />
      </div>
      <div class="asset-item">
        <span class="asset-label">京东 Plus 会员</span>
        <el-switch v-model="assets.jdPlus" />
      </div>
      <div class="asset-item">
        <span class="asset-label">国补地区</span>
        <el-select
          v-model="assets.govSubsidyRegion"
          placeholder="选择地区"
          clearable
          style="width: 140px"
        >
          <el-option
            v-for="r in regions"
            :key="r"
            :label="r"
            :value="r"
          />
        </el-select>
      </div>
      <p class="asset-tip">
        设置后将影响到手价计算。数据仅存储在本地浏览器。
      </p>
    </div>
  </el-drawer>
</template>

<style scoped>
.asset-form {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.asset-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.asset-label {
  font-size: 14px;
  color: #303133;
}

.asset-tip {
  font-size: 12px;
  color: #909399;
  margin-top: 8px;
}
</style>
