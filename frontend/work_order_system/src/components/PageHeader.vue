<script setup lang="ts">
/**
 * 页面标题区
 *
 * 统一每个页面顶部的节奏:一行小号大写字距的"眉毛"文案 + 主标题 + 描述 + 右侧操作区。
 * 眉毛那行是让页面"看起来被设计过"的低成本手段,也顺便说明这个页面是干什么的。
 */
withDefaults(
  defineProps<{
    /** 主标题 */
    title: string
    /** 眉毛文案,不传则不渲染 */
    eyebrow?: string
    /** 一句话说明,不传则不渲染 */
    description?: string
  }>(),
  { eyebrow: '', description: '' },
)
</script>

<template>
  <header class="page-header">
    <div class="page-header__text">
      <p v-if="eyebrow" class="wo-eyebrow">{{ eyebrow }}</p>
      <h1 class="page-header__title">{{ title }}</h1>
      <p v-if="description" class="page-header__desc">{{ description }}</p>
    </div>

    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped lang="scss">
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--wo-space-5);
  padding-bottom: var(--wo-space-4);
  margin-bottom: var(--wo-space-5);
  border-bottom: 1px solid var(--wo-hairline);
}

.page-header__text {
  min-width: 0;
}

.page-header__title {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--wo-ink-1);
  margin-top: 6px;
}

.page-header__desc {
  margin-top: 6px;
  font-size: 13px;
  color: var(--wo-ink-3);
  max-width: 62ch;
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: var(--wo-space-2);
  flex: none;
}
</style>
