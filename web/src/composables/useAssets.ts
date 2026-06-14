import { reactive, watch } from 'vue'
import type { UserAssets } from '@/api/types'

const STORAGE_KEY = 'shengxin_assets'

function loadAssets(): UserAssets {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (raw) return JSON.parse(raw)
  } catch {}
  return { vip88: false, jdPlus: false, pddMonthly: false, govSubsidyRegion: null }
}

const assets = reactive<UserAssets>(loadAssets())

watch(assets, (val) => {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(val))
}, { deep: true })

export function useAssets() {
  return { assets }
}
