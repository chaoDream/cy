<script setup lang="ts">
import SearchBar from '@/components/home/SearchBar.vue'
import BrandQuickFilter from '@/components/home/BrandQuickFilter.vue'
import RecommendGrid from '@/components/home/RecommendGrid.vue'
import MiniProgramBanner from '@/components/common/MiniProgramBanner.vue'
</script>

<template>
  <div class="home-page">
    <section class="hero">
      <h1 class="hero-title">一键看透手机真实到手价</h1>
      <p class="hero-subtitle">
        搜索手机型号或粘贴京东商品链接，AI 帮你拆解价格、识别套路、给出购买建议
      </p>
      <SearchBar />
    </section>

    <BrandQuickFilter />
    <RecommendGrid />
    <MiniProgramBanner />
  </div>
</template>

<style scoped>
.home-page {
  padding-bottom: 60px;
}

.hero {
  text-align: center;
  padding: 48px 0 32px;
}

.hero-title {
  font-size: 36px;
  font-weight: 800;
  color: #1a1a2e;
  margin-bottom: 12px;
}

.hero-subtitle {
  font-size: 16px;
  color: #606266;
  margin-bottom: 32px;
  line-height: 1.6;
}
</style>
