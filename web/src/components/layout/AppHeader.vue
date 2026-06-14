<script setup lang="ts">
import { ref } from 'vue'
import { Setting } from '@element-plus/icons-vue'
import AssetDrawer from '../common/AssetDrawer.vue'

const drawerVisible = ref(false)
</script>

<template>
  <header class="app-header">
    <div class="header-inner">
      <router-link to="/" class="logo">
        <span class="logo-icon">💰</span>
        <span class="logo-text">省心买</span>
      </router-link>
      <nav class="nav-links">
        <router-link to="/" class="nav-item">首页</router-link>
        <router-link to="/rank" class="nav-item">低价榜</router-link>
      </nav>
      <div class="header-actions">
        <el-button :icon="Setting" circle @click="drawerVisible = true" />
      </div>
    </div>
    <AssetDrawer v-model="drawerVisible" />
  </header>
</template>

<style scoped>
.app-header {
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
  position: sticky;
  top: 0;
  z-index: 100;
}

.header-inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 20px;
  height: 60px;
  display: flex;
  align-items: center;
  gap: 32px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 20px;
  font-weight: 700;
  color: #e6393d;
}

.logo-icon {
  font-size: 24px;
}

.nav-links {
  display: flex;
  gap: 24px;
}

.nav-item {
  font-size: 15px;
  color: #606266;
  padding: 4px 0;
  border-bottom: 2px solid transparent;
  transition: color 0.2s, border-color 0.2s;
}

.nav-item:hover,
.nav-item.router-link-exact-active {
  color: #e6393d;
  border-bottom-color: #e6393d;
}

.header-actions {
  margin-left: auto;
}
</style>
