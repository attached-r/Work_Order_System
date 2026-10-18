<script setup lang="ts">
/**
 * 会话栏
 *
 * 只负责展示与交互,数据的增删选由父页面通过事件发起。
 * 分组按「本地日历日」计算,不切 ISO 字符串 —— 后者是 UTC,会和中国时区差 8 小时。
 */
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Close, Delete, Plus } from '@element-plus/icons-vue'
import { formatRelative } from '@/utils/datetime'
import type { Conversation } from '@/stores/agent'

const props = defineProps<{
  conversations: Conversation[]
  activeId: string | null
  /** 正在流式输出的会话 id,用于显示脉冲小点 */
  streamingConversationId: string | null
}>()

const emit = defineEmits<{
  select: [id: string]
  new: []
  delete: [id: string]
  close: []
}>()

interface DateGroup {
  label: string
  items: Conversation[]
}

/** 取本地零点的时间戳 */
function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

const groups = computed<DateGroup[]>(() => {
  // 步骤 1:用 setDate/setHours 往前推,避免直接减 86400000 在夏令时边界差一小时
  const today = new Date()
  const todayStart = startOfDay(today)

  const yesterdayDate = new Date(today)
  yesterdayDate.setDate(yesterdayDate.getDate() - 1)
  const yesterdayStart = startOfDay(yesterdayDate)

  const weekAgoDate = new Date(today)
  weekAgoDate.setDate(weekAgoDate.getDate() - 6)
  const weekAgoStart = startOfDay(weekAgoDate)

  // 步骤 2:按更新时间倒序分桶
  const buckets = new Map<string, Conversation[]>()
  const ordered = [...props.conversations].sort((a, b) => b.updatedAt - a.updatedAt)

  for (const conversation of ordered) {
    let label = '更早'
    if (conversation.updatedAt >= todayStart) label = '今天'
    else if (conversation.updatedAt >= yesterdayStart) label = '昨天'
    else if (conversation.updatedAt >= weekAgoStart) label = '近 7 天'

    const list = buckets.get(label)
    if (list) list.push(conversation)
    else buckets.set(label, [conversation])
  }

  // 步骤 3:按固定顺序输出有内容的分组
  const order = ['今天', '昨天', '近 7 天', '更早']
  return order
    .filter((label) => buckets.has(label))
    .map((label) => ({ label, items: buckets.get(label) ?? [] }))
})

/** 会话项的相对时间:updatedAt 是毫秒时间戳,formatRelative 需要字符串 */
function relativeTime(updatedAt: number): string {
  return formatRelative(new Date(updatedAt).toISOString())
}

async function handleDelete(conversation: Conversation): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除后无法恢复,确定删除「${conversation.title}」吗?`,
      '删除对话',
      {
        confirmButtonText: '删除',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return // 用户取消
  }
  emit('delete', conversation.id)
}
</script>

<template>
  <aside class="rail">
    <header class="rail__head">
      <div class="rail__heading">
        <span class="wo-eyebrow">Conversations</span>
        <b>对话记录</b>
      </div>

      <!-- 只在移动端抽屉里出现,桌面端由 CSS 隐藏 -->
      <button type="button" class="rail__close" title="关闭" @click="emit('close')">
        <el-icon :size="16"><Close /></el-icon>
      </button>
    </header>

    <button type="button" class="rail__new" @click="emit('new')">
      <el-icon :size="15"><Plus /></el-icon>
      <span>新对话</span>
    </button>

    <div class="rail__list">
      <section v-for="group in groups" :key="group.label" class="rail-group">
        <p class="rail-group__label">{{ group.label }}</p>

        <ul class="rail-group__items">
          <li
            v-for="conversation in group.items"
            :key="conversation.id"
            class="rail-conv"
            :class="{ 'is-active': conversation.id === activeId }"
          >
            <!-- 主按钮与会话项是兄弟节点,避免 button 嵌套 button -->
            <button type="button" class="rail-conv__main" @click="emit('select', conversation.id)">
              <span class="rail-conv__title">{{ conversation.title }}</span>
              <span class="rail-conv__meta">
                <span
                  v-if="conversation.id === streamingConversationId"
                  class="rail-conv__dot"
                  aria-label="正在生成"
                ></span>
                <span class="rail-conv__time">{{ relativeTime(conversation.updatedAt) }}</span>
              </span>
            </button>

            <button
              type="button"
              class="rail-conv__del"
              title="删除对话"
              @click.stop="handleDelete(conversation)"
            >
              <el-icon :size="13"><Delete /></el-icon>
            </button>
          </li>
        </ul>
      </section>

      <p v-if="conversations.length === 0" class="rail__empty">还没有对话</p>
    </div>

    <p class="rail__foot">对话记录仅保存在本机浏览器</p>
  </aside>
</template>

<style scoped lang="scss">
.rail {
  width: 268px;
  flex: none;
  display: flex;
  flex-direction: column;
  min-height: 0;
  padding: 14px 12px 12px;
  background: linear-gradient(180deg, #fbfdff 0%, #f7fafe 100%);
  border-right: 1px solid var(--wo-hairline);
}

.rail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  padding: 0 4px 12px;
}

.rail__heading {
  display: flex;
  flex-direction: column;
  line-height: 1.3;

  b {
    margin-top: 2px;
    font-size: 14px;
    font-weight: 700;
    color: var(--wo-ink-1);
  }
}

.rail__close {
  display: none;
  width: 28px;
  height: 28px;
  place-items: center;
  border: none;
  border-radius: var(--wo-radius-sm);
  background: transparent;
  color: var(--wo-ink-3);
  cursor: pointer;

  &:hover {
    background: var(--wo-brand-wash);
    color: var(--wo-brand);
  }
}

// 新对话:描边按钮,不抢主按钮的视觉
.rail__new {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  width: 100%;
  flex: none;
  padding: 8px 10px;
  border: 1px solid var(--wo-hairline-brand);
  border-radius: var(--wo-radius);
  background: var(--wo-surface);
  color: var(--wo-brand);
  font-family: inherit;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition:
    background-color var(--wo-dur-fast) var(--wo-ease),
    border-color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: var(--wo-brand-wash);
    border-color: var(--wo-brand);
  }
}

.rail__list {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  margin-top: 12px;
  padding-right: 2px;
}

.rail-group + .rail-group {
  margin-top: 14px;
}

.rail-group__label {
  padding: 0 8px 6px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--wo-ink-4);
}

.rail-group__items {
  list-style: none;
  margin: 0;
  padding: 0;
}

.rail-conv {
  position: relative;
  display: flex;
  align-items: center;
  border-radius: var(--wo-radius-sm);
  transition: background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: var(--wo-brand-wash);
  }

  &.is-active {
    background: var(--wo-brand-wash-2);

    // 左侧一道主色竖条,比整块深色更轻
    &::before {
      content: '';
      position: absolute;
      left: 0;
      top: 7px;
      bottom: 7px;
      width: 3px;
      border-radius: 0 2px 2px 0;
      background: var(--wo-brand);
    }
  }
}

.rail-conv__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 10px 8px 12px;
  border: none;
  border-radius: var(--wo-radius-sm);
  background: transparent;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
}

.rail-conv__title {
  width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 13px;
  color: var(--wo-ink-2);
}

.rail-conv.is-active .rail-conv__title {
  color: var(--wo-brand-hover);
  font-weight: 600;
}

.rail-conv__meta {
  display: flex;
  align-items: center;
  gap: 5px;
}

.rail-conv__time {
  font-size: 10.5px;
  color: var(--wo-ink-4);
}

// 正在生成的会话:一个小脉冲点
.rail-conv__dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--wo-brand);
  animation: rail-pulse 1.2s infinite ease-in-out;
}

@keyframes rail-pulse {
  0%,
  100% {
    opacity: 0.35;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1.15);
  }
}

// 删除按钮默认隐藏,悬浮/聚焦/触屏时出现
.rail-conv__del {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  margin-right: 4px;
  flex: none;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--wo-ink-4);
  cursor: pointer;
  opacity: 0;
  transition:
    opacity var(--wo-dur-fast) var(--wo-ease),
    color var(--wo-dur-fast) var(--wo-ease),
    background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    color: var(--wo-st-5-fg);
    background: var(--wo-st-5-bg);
  }
}

.rail-conv:hover .rail-conv__del,
.rail-conv:focus-within .rail-conv__del {
  opacity: 1;
}

.rail__empty {
  padding: 28px 8px;
  text-align: center;
  font-size: 12px;
  color: var(--wo-ink-4);
}

.rail__foot {
  flex: none;
  padding: 10px 4px 2px;
  border-top: 1px solid var(--wo-hairline);
  font-size: 10.5px;
  color: var(--wo-ink-4);
  text-align: center;
}

@media (max-width: 960px) {
  .rail {
    width: 280px;
    box-shadow: var(--wo-shadow-md);
  }

  .rail__close {
    display: grid;
  }
}

@media (hover: none) {
  .rail-conv__del {
    opacity: 1;
  }
}
</style>
