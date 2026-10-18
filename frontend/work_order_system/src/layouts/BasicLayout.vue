<script setup lang="ts">
/**
 * 主布局:左侧固定导航 + 右侧(顶栏 + 内容区)
 *
 * 内容区用 max-width 兜底,避免在 2K/4K 屏上一行拉到 2000px 难读;
 * 同时 padding 用 clamp,窄屏自动收紧。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppSidebar from './components/AppSidebar.vue'
import AppHeader from './components/AppHeader.vue'

const route = useRoute()

/**
 * 聊天页这类「满幅」页面需要把内容区的高度完整吃下来:
 * 默认的内容区是整页滚动、内层有最大宽度和内边距,聊天页如果照用,
 * 输入框会跟着整页滚走。fullBleed 时改为内容区不滚、内层撑满,
 * 由页面自己在消息区内部滚动。
 */
const fullBleed = computed(() => Boolean(route.meta.fullBleed))
</script>

<template>
  <div class="layout">
    <AppSidebar />

    <div class="layout__main">
      <AppHeader />

      <main class="layout__content" :class="{ 'is-full-bleed': fullBleed }">
        <div class="layout__inner" :class="{ 'is-full-bleed': fullBleed }">
          <RouterView v-slot="{ Component, route }">
            <Transition name="wo-fade" mode="out-in">
              <component :is="Component" :key="route.path" />
            </Transition>
          </RouterView>
        </div>
      </main>
    </div>
  </div>
</template>

<style scoped lang="scss">
.layout {
  display: flex;
  height: 100%;
  overflow: hidden;
  background: var(--wo-canvas);
}

.layout__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.layout__content {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  // 极淡的点阵网格:给纯色底一点肌理,几乎看不见但能让大面积留白不"死"
  background-image: radial-gradient(circle at 1px 1px, rgba(47, 111, 237, 0.055) 1px, transparent 0);
  background-size: 22px 22px;
}

.layout__inner {
  max-width: 1560px;
  margin: 0 auto;
  padding: clamp(16px, 2.4vw, 28px);
}

// ---- 满幅模式(聊天页) ----
// 内容区不再整页滚动,点阵背景也去掉 —— 聊天页自身是干净的整块画布;
// 高度从 #app 一路传下来,交给页面内部的「消息区滚动 + 输入区固定」。
.layout__content.is-full-bleed {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background-image: none;
}

.layout__inner.is-full-bleed {
  flex: 1;
  min-height: 0;
  width: 100%;
  max-width: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
}
</style>
