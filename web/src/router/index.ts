import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/',
      name: 'home',
      component: () => import('@/views/HomePage.vue'),
      meta: { title: '省心买' },
    },
    {
      path: '/analysis',
      name: 'analysis',
      component: () => import('@/views/AnalysisPage.vue'),
      meta: { title: '商品分析' },
    },
    {
      path: '/rank',
      name: 'rank',
      component: () => import('@/views/RankPage.vue'),
      meta: { title: '手机低价榜' },
    },
  ],
})

router.afterEach((to) => {
  document.title = (to.meta.title as string) || '省心买'
})

export default router
