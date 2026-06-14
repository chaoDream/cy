<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Search } from '@element-plus/icons-vue'
import { parseLink, searchProducts } from '@/api/product'
import type { SearchItem } from '@/api/types'

const router = useRouter()
const query = ref('')
const loading = ref(false)
const searchResults = ref<SearchItem[]>([])
const showDropdown = ref(false)

function isLink(text: string): boolean {
  return /jd\.com|3\.cn|u\.jd\.com|item\.jd|https?:\/\//.test(text)
}

async function handleSubmit() {
  const text = query.value.trim()
  if (!text) return

  loading.value = true
  try {
    if (isLink(text)) {
      const result = await parseLink(text)
      router.push({
        path: '/analysis',
        query: { platform: result.platform, item_id: result.itemId },
      })
    } else {
      const results = await searchProducts(text)
      searchResults.value = results
      showDropdown.value = true
    }
  } catch (e: any) {
    ElMessage.error(e.message || '查询失败')
  } finally {
    loading.value = false
  }
}

function selectProduct(item: SearchItem) {
  showDropdown.value = false
  query.value = ''
  router.push({
    path: '/analysis',
    query: { platform: item.platform, item_id: item.itemId },
  })
}

function platformName(p: string): string {
  const map: Record<string, string> = { jd: '京东', pdd: '拼多多', tb: '淘宝', dy: '抖音' }
  return map[p] || p
}
</script>

<template>
  <div class="search-bar-wrap">
    <div class="search-input-row">
      <el-input
        v-model="query"
        size="large"
        placeholder="搜索手机型号（如 iPhone 16 Pro）或粘贴京东商品链接"
        :prefix-icon="Search"
        clearable
        @keyup.enter="handleSubmit"
        @clear="showDropdown = false"
      />
      <el-button
        type="danger"
        size="large"
        :loading="loading"
        @click="handleSubmit"
      >
        查价
      </el-button>
    </div>
    <div v-if="showDropdown && searchResults.length > 0" class="search-dropdown">
      <div
        v-for="item in searchResults"
        :key="`${item.platform}-${item.itemId}`"
        class="search-result-item"
        @click="selectProduct(item)"
      >
        <img
          v-if="item.imageUrl"
          :src="item.imageUrl"
          class="result-img"
          alt=""
        />
        <div class="result-info">
          <span class="result-title">{{ item.title }}</span>
          <span class="result-meta">
            <el-tag size="small" :type="item.platform === 'jd' ? 'danger' : 'warning'">
              {{ platformName(item.platform) }}
            </el-tag>
            <span v-if="item.rawPrice" class="result-price">¥{{ item.rawPrice }}</span>
          </span>
        </div>
      </div>
    </div>
    <div v-if="showDropdown && searchResults.length === 0 && !loading" class="search-dropdown">
      <div class="search-empty">未找到相关商品</div>
    </div>
  </div>
</template>

<style scoped>
.search-bar-wrap {
  position: relative;
  max-width: 700px;
  margin: 0 auto;
}

.search-input-row {
  display: flex;
  gap: 12px;
}

.search-input-row :deep(.el-input) {
  flex: 1;
}

.search-dropdown {
  position: absolute;
  top: 100%;
  left: 0;
  right: 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.1);
  margin-top: 4px;
  max-height: 400px;
  overflow-y: auto;
  z-index: 50;
}

.search-result-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  cursor: pointer;
  transition: background 0.15s;
}

.search-result-item:hover {
  background: #f5f7fa;
}

.result-img {
  width: 48px;
  height: 48px;
  object-fit: contain;
  border-radius: 4px;
  background: #f5f7fa;
}

.result-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
}

.result-title {
  font-size: 14px;
  color: #303133;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.result-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}

.result-price {
  font-size: 13px;
  color: #e6393d;
  font-weight: 600;
}

.search-empty {
  padding: 24px;
  text-align: center;
  color: #909399;
  font-size: 14px;
}
</style>
