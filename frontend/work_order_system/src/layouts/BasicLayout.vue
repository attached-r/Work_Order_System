<script setup lang="ts">
/**
 * 主布局:左侧固定导航 + 右侧(顶栏 + 内容区)
 *
 * 内容区用 max-width 兜底,避免在 2K/4K 屏上一行拉到 2000px 难读;
 * 同时 padding 用 clamp,窄屏自动收紧。
 */
import AppSidebar from './components/AppSidebar.vue'
import AppHeader from './components/AppHeader.vue'
</script>

<template>
  <div class="layout">
    <AppSidebar />

    <div class="layout__main">
      <AppHeader />

      <main class="layout__content">
        <div class="layout__inner">
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
</style>
