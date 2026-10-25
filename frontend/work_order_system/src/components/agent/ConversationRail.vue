<script setup lang="ts">
/**
 * 会话栏
 *
 * 只负责展示与交互,数据的增删选由父页面通过事件发起。
 * 分组按「本地日历日」计算,不切 ISO 字符串 —— 后者是 UTC,会和中国时区差 8 小时。
 */
import { computed } from 'vue'
import { ElMessageBox } from 'element-plus'
import { Close, MoreFilled, Plus } from '@element-plus/icons-vue'
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
  rename: [id: string, title: string]
  export: [conversation: Conversation]
  clear: []
  close: []
}>()

/** 会话项下拉菜单的命令 */
type ConvCommand = 'rename' | 'export' | 'delete'

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

async function handleRename(conversation: Conversation): Promise<void> {
  let raw: unknown
  try {
    // prompt 的返回值是联合类型,先按 unknown 接住再窄化,避免直接读 .value
    const result: unknown = await ElMessageBox.prompt('输入新的对话标题', '重命名对话', {
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValue: conversation.title,
      inputPlaceholder: '对话标题',
      inputValidator: (value: string) => {
        const title = value.trim()
        if (!title) return '标题不能为空'
        if (Array.from(title).length > 40) return '标题不能超过 40 个字符'
        return true
      },
    })
    raw = typeof result === 'object' && result !== null && 'value' in result ? result.value : null
  } catch {
    return // 用户取消
  }

  if (typeof raw !== 'string') return
  const title = raw.trim()
  // 没改就不落盘,也不必刷新列表
  if (!title || title === conversation.title) return
  emit('rename', conversation.id, title)
}

/** 下拉菜单统一入口:重命名 / 导出 / 删除 */
function handleCommand(command: unknown, conversation: Conversation): void {
  if (command === 'rename') {
    void handleRename(conversation)
  } else if (command === 'export') {
    emit('export', conversation)
  } else if (command === 'delete') {
    void handleDelete(conversation)
  }
}

async function handleClearAll(): Promise<void> {
  try {
    await ElMessageBox.confirm(
      '清空后当前账号的对话记录都会从本机删除且无法恢复' +
        (props.streamingConversationId ? '，正在生成的回答也会立即停止。' : '。'),
      '清空当前账号的对话',
      {
        confirmButtonText: '清空',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return // 用户取消
  }
  emit('clear')
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

            <!-- 更多操作:触发器必须是主按钮的兄弟节点,不能嵌套 button -->
            <el-dropdown
              trigger="click"
              placement="bottom-end"
              @command="handleCommand($event, conversation)"
            >
              <button type="button" class="rail-conv__more" title="更多操作">
                <el-icon :size="13"><MoreFilled /></el-icon>
              </button>

              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="rename">重命名</el-dropdown-item>
                  <el-dropdown-item command="export">导出 Markdown</el-dropdown-item>
                  <el-dropdown-item command="delete" divided>删除</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </li>
        </ul>
      </section>

      <p v-if="conversations.length === 0" class="rail__empty">还没有对话</p>
    </div>

    <div class="rail__foot">
      <button
        v-if="conversations.length > 0"
        type="button"
        class="rail__clear"
        @click="handleClearAll"
      >
        清空当前账号
      </button>
      <p>对话记录按账号保存在本机浏览器</p>
    </div>
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

// 更多操作默认隐藏,悬浮/聚焦/触屏时出现
.rail-conv__more {
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
    color: var(--wo-brand);
    background: var(--wo-brand-wash);
  }
}

.rail-conv:hover .rail-conv__more,
.rail-conv:focus-within .rail-conv__more {
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
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 8px 4px 2px;
  border-top: 1px solid var(--wo-hairline);
  font-size: 10.5px;
  color: var(--wo-ink-4);
  text-align: center;

  p {
    margin: 0;
  }
}

.rail__clear {
  padding: 2px 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--wo-ink-4);
  font-family: inherit;
  font-size: 11px;
  cursor: pointer;
  transition:
    color var(--wo-dur-fast) var(--wo-ease),
    background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    color: var(--wo-st-5-fg);
    background: var(--wo-st-5-bg);
  }
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
  .rail-conv__more {
    opacity: 1;
  }
}
</style>
