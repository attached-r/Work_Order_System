<script setup lang="ts">
/**
 * 工单状态标签
 *
 * 视觉约定:淡色底 + 同色系深字 + 前置小圆点,不用边框。
 * 圆点比纯色块更轻,在密集表格里不会糊成一片。
 * 全部颜色取自 tokens.scss 的 --wo-st-{n}-* ,组件本身不写死色值。
 */
import { computed } from 'vue'
import { getStatusLabel, getStatusTone } from '@/constants/workorder'

const props = withDefaults(
  defineProps<{
    /** 状态码 0-7 */
    status: number | null | undefined
    size?: 'small' | 'default'
  }>(),
  { size: 'default' },
)

const label = computed(() => getStatusLabel(props.status))
const tone = computed(() => getStatusTone(props.status))
</script>

<template>
  <span
    class="status-tag"
    :class="[`status-tag--${size}`]"
    :style="{
      '--fg': `var(--wo-st-${tone}-fg)`,
      '--bg': `var(--wo-st-${tone}-bg)`,
      '--dot': `var(--wo-st-${tone}-dot)`,
    }"
  >
    <i class="status-tag__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped lang="scss">
.status-tag {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 10px 2px 8px;
  border-radius: 999px;
  background: var(--bg);
  color: var(--fg);
  font-size: 12px;
  font-weight: 600;
  line-height: 20px;
  white-space: nowrap;
  // 让状态在表格里"立"起来一点,但仍是平的不抢眼
  letter-spacing: 0.01em;
}

.status-tag__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--dot);
  flex: none;
}

.status-tag--small {
  padding: 1px 8px 1px 6px;
  font-size: 11px;
  line-height: 18px;
  gap: 5px;

  .status-tag__dot {
    width: 5px;
    height: 5px;
  }
}
</style>
