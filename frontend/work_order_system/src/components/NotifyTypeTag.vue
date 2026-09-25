<script setup lang="ts">
/**
 * 通知类型标签
 *
 * 视觉与 StatusTag / PriorityTag 同一套:淡色底 + 同色系深字 + 前置小圆点,不用边框。
 * 色值全部取自 tokens.scss 的 --wo-st-{n}-*(与工单状态共用一支色阶),
 * 组件本身不写死色值 —— 换主题只改令牌。
 */
import { computed } from 'vue'
import { getNotifyTone, getNotifyTypeLabel } from '@/constants/notification'

const props = withDefaults(
  defineProps<{
    /** 类型码 1-10,取值同工单操作事件 */
    type: number | null | undefined
    /** 后端渲染好的中文名;为空(null)时回落到本地枚举表 */
    desc?: string | null
    size?: 'small' | 'default'
  }>(),
  { desc: null, size: 'default' },
)

const label = computed(() => getNotifyTypeLabel(props.type, props.desc))
const tone = computed(() => getNotifyTone(props.type))
</script>

<template>
  <span
    class="notify-tag"
    :class="[`notify-tag--${size}`]"
    :style="{
      '--fg': `var(--wo-st-${tone}-fg)`,
      '--bg': `var(--wo-st-${tone}-bg)`,
      '--dot': `var(--wo-st-${tone}-dot)`,
    }"
  >
    <i class="notify-tag__dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped lang="scss">
.notify-tag {
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
  letter-spacing: 0.01em;
}

.notify-tag__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--dot);
  flex: none;
}

.notify-tag--small {
  padding: 1px 8px 1px 6px;
  font-size: 11px;
  line-height: 18px;
  gap: 5px;

  .notify-tag__dot {
    width: 5px;
    height: 5px;
  }
}
</style>
