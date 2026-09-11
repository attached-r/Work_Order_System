<script setup lang="ts">
/**
 * 优先级标签
 *
 * 和状态标签区分开:状态是"圆点 + 淡底",优先级用"纯文字 + 色"表达,
 * 视觉重量更轻,避免每一列都在抢注意力。
 * 高优先级额外加一条左侧竖线,让"高"在一屏数据里真的跳出来。
 */
import { computed } from 'vue'
import { getPriorityLabel, getPriorityTone } from '@/constants/workorder'

const props = defineProps<{
  priority: number | null | undefined
}>()

const label = computed(() => getPriorityLabel(props.priority))
const tone = computed(() => getPriorityTone(props.priority))
const isHigh = computed(() => props.priority === 1)
</script>

<template>
  <span
    class="priority"
    :class="{ 'is-high': isHigh }"
    :style="{ '--fg': `var(--wo-pr-${tone}-fg)`, '--dot': `var(--wo-pr-${tone}-dot)` }"
  >
    {{ label }}
  </span>
</template>

<style scoped lang="scss">
.priority {
  display: inline-flex;
  align-items: center;
  color: var(--fg);
  font-size: 13px;
  font-weight: 600;
}

// 高优先级:左侧一条短竖线,是整列里唯一的"形状"变化
.priority.is-high {
  padding-left: 8px;
  position: relative;

  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 50%;
    transform: translateY(-50%);
    width: 3px;
    height: 12px;
    border-radius: 2px;
    background: var(--dot);
  }
}
</style>
