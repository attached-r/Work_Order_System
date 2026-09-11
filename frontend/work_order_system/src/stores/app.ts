/**
 * 全局 UI 状态
 *
 * 只放「与业务无关、但多个布局组件都要读」的东西:侧边栏折叠、当前页标题。
 * 不往这里塞业务数据,避免变成一个什么都装的筐。
 */
import { ref } from 'vue'
import { defineStore } from 'pinia'

const COLLAPSE_KEY = 'wo_sidebar_collapsed'

export const useAppStore = defineStore('app', () => {
  const sidebarCollapsed = ref(localStorage.getItem(COLLAPSE_KEY) === '1')

  function toggleSidebar() {
    sidebarCollapsed.value = !sidebarCollapsed.value
    localStorage.setItem(COLLAPSE_KEY, sidebarCollapsed.value ? '1' : '0')
  }

  return { sidebarCollapsed, toggleSidebar }
})
