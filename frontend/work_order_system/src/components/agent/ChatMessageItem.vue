<script setup lang="ts">
/**
 * 单条消息
 *
 * 用户消息走纯文本气泡(不做 Markdown),助手消息走 Markdown。
 * 这样既能避免用户输入里的 `*` / `#` 被误渲染,也让两类消息在视觉上区分得开。
 */
import { computed, nextTick, ref } from 'vue'
import { CopyDocument, EditPen, MagicStick, RefreshRight } from '@element-plus/icons-vue'
import AgentMarkdown from './AgentMarkdown.vue'
import WriteConfirmBar from './WriteConfirmBar.vue'
import { useUserStore } from '@/stores/user'
import { formatClock } from '@/utils/datetime'
import { MAX_MESSAGE_LENGTH } from '@/api/agent'
import type { ChatMessage } from '@/stores/agent'
import type { WorkOrderRef } from '@/utils/workOrderRef'
import type { WritePreviewInfo } from '@/utils/writePreview'

const props = defineProps<{
  message: ChatMessage
  /** 是否是当前最后一条 assistant 消息(只有它可以重新生成) */
  isLastAssistant: boolean
  /** 是否是当前最后一条 user 消息(只有它可以编辑后重发) */
  isLastUser: boolean
  /** 任意一条消息正在流式输出(此时禁用重新生成,避免并发) */
  busy: boolean
  /** 是否把助手回答里的工单引用渲染成链接 */
  enableWorkOrderLinks: boolean
  /**
   * M4:最后一条助手回答被识别为「等待确认的写操作预览」时传入。
   * 历史消息不传,避免过期预览一直挂着确认按钮。
   */
  writePreview?: WritePreviewInfo | null
  /** 无 workorder:query 时隐藏预览卡片里的「查看工单」 */
  canOpenPreviewOrder?: boolean
}>()

const emit = defineEmits<{
  copy: [text: string]
  regenerate: []
  retry: []
  edit: [messageId: string, text: string]
  'open-workorder': [ref: WorkOrderRef]
  'confirm-write': []
}>()

const userStore = useUserStore()

const isUser = computed(() => props.message.role === 'user')
const isStreaming = computed(() => props.message.status === 'streaming')
const time = computed(() => formatClock(new Date(props.message.createdAt).toISOString()))

/** 错误/停止状态需要额外的提示条;正常结束不显示 */
const showError = computed(() => props.message.status === 'error')
const showStopped = computed(() => props.message.status === 'stopped')

/**
 * 打开预览卡片里的工单。
 * 只传内部 ID:预览 JSON 里的 orderNo 可能缺失,而页面跳转只认 ID。
 */
function openPreviewOrder(): void {
  const id = props.writePreview?.data.orderId
  if (id === undefined || id <= 0) return
  emit('open-workorder', { id })
}

// ---------------------------------------------------------------------------
// 用户消息的内联编辑
// ---------------------------------------------------------------------------

const editing = ref(false)
const editText = ref('')
const editTextareaRef = ref<HTMLTextAreaElement | null>(null)
/** 中文输入法组合期间的回车是「选词」,不能当成提交 */
const composing = ref(false)

const canEdit = computed(
  () => isUser.value && props.isLastUser && !props.busy && !isStreaming.value,
)

const canSubmitEdit = computed(() => {
  const text = editText.value.trim()
  return text.length > 0 && text.length <= MAX_MESSAGE_LENGTH
})

function startEdit(): void {
  editText.value = props.message.content
  editing.value = true
  // 等 textarea 渲染出来再聚焦,并把光标放到末尾
  void nextTick(() => {
    const el = editTextareaRef.value
    if (!el) return
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
  })
}

function cancelEdit(): void {
  editing.value = false
  editText.value = ''
}

function submitEdit(): void {
  const text = editText.value.trim()
  // 没改动就不重发;父组件的「重新生成」已经覆盖了这个场景
  if (!text || text === props.message.content) {
    cancelEdit()
    return
  }
  emit('edit', props.message.id, text)
  editing.value = false
}

/** Enter 提交、Shift+Enter 换行、Esc 取消(阻止冒泡,避免触发全局「停止生成」) */
function handleEditKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    cancelEdit()
    return
  }

  if (event.key !== 'Enter' || event.shiftKey) return
  if (composing.value || event.isComposing || event.keyCode === 229) return

  event.preventDefault()
  if (canSubmitEdit.value) submitEdit()
}
</script>

<template>
  <article class="msg" :class="isUser ? 'msg--user' : 'msg--assistant'">
    <!-- 头像:用户用姓名首字,助手用品牌渐变 + 魔杖图标 -->
    <div class="msg__avatar" :class="isUser ? 'msg__avatar--user' : 'msg__avatar--agent'">
      <template v-if="isUser">{{ userStore.initial }}</template>
      <el-icon v-else :size="16"><MagicStick /></el-icon>
    </div>

    <div class="msg__main">
      <header class="msg__meta">
        <b>{{ isUser ? '我' : '工单 Agent' }}</b>
        <time class="wo-num" :datetime="new Date(message.createdAt).toISOString()">{{ time }}</time>
      </header>

      <!-- 用户:纯文本气泡;编辑时原地换成 textarea -->
      <template v-if="isUser">
        <div v-if="!editing" class="msg__bubble">{{ message.content }}</div>

        <div v-else class="msg__editor">
          <textarea
            ref="editTextareaRef"
            v-model="editText"
            class="msg__editor-input"
            :maxlength="MAX_MESSAGE_LENGTH"
            rows="3"
            @keydown="handleEditKeydown"
            @compositionstart="composing = true"
            @compositionend="composing = false"
          ></textarea>

          <div class="msg__editor-bar">
            <span class="wo-num msg__editor-count">
              {{ editText.length }}/{{ MAX_MESSAGE_LENGTH }}
            </span>
            <button type="button" class="msg__editor-btn" @click="cancelEdit">取消</button>
            <button
              type="button"
              class="msg__editor-btn msg__editor-btn--primary"
              :disabled="!canSubmitEdit"
              @click="submitEdit"
            >
              保存并重发
            </button>
          </div>
        </div>
      </template>

      <!-- 助手:Markdown 正文 -->
      <div v-else class="msg__answer">
        <!-- 流式开始但还没有任何 token:三点等待,避免空气泡 -->
        <span v-if="isStreaming && !message.content" class="thinking" aria-label="正在思考">
          <i></i><i></i><i></i>
        </span>

        <AgentMarkdown
          v-if="message.content"
          :content="message.content"
          :streaming="isStreaming"
          :enable-work-order-links="enableWorkOrderLinks"
          @open-workorder="emit('open-workorder', $event)"
        />

        <div v-if="showError" class="msg__notice msg__notice--error">
          <span>{{ message.error || '生成失败,请重试' }}</span>
          <!-- 只有最后一条可重试;历史错误只做展示,避免错误地重跑后面的问题 -->
          <button
            v-if="isLastAssistant && !busy"
            type="button"
            class="msg__notice-btn"
            @click="emit('retry')"
          >
            重试
          </button>
        </div>

        <div v-else-if="showStopped" class="msg__notice">
          <span>已停止生成</span>
          <button
            v-if="isLastAssistant && !busy"
            type="button"
            class="msg__notice-btn"
            @click="emit('regenerate')"
          >
            重新生成
          </button>
        </div>

        <!--
          M4：写操作预览的确认入口。
          只在「最后一条、正常结束、当前没有流式输出」时出现；
          历史消息即使文本里还留着预览，也不再给按钮——令牌可能早已过期。
        -->
        <WriteConfirmBar
          v-if="writePreview && isLastAssistant && !busy && !isStreaming"
          :info="writePreview"
          :busy="busy"
          :can-open-order="canOpenPreviewOrder ?? false"
          @confirm="emit('confirm-write')"
          @open-order="openPreviewOrder"
        />
      </div>

      <!-- 操作行:平时低对比,悬浮/聚焦才显形;编辑中隐藏,避免误触 -->
      <footer v-if="message.content && !editing" class="msg__actions">
        <button type="button" title="复制" @click="emit('copy', message.content)">
          <el-icon :size="14"><CopyDocument /></el-icon>
          <span>复制</span>
        </button>

        <!-- 只有最后一条用户消息可以编辑;改了它意味着后续回答全部作废 -->
        <button v-if="canEdit" type="button" title="编辑并重新发送" @click="startEdit">
          <el-icon :size="14"><EditPen /></el-icon>
          <span>编辑</span>
        </button>

        <button
          v-if="!isUser && isLastAssistant && !busy && !isStreaming"
          type="button"
          title="重新生成"
          @click="emit('regenerate')"
        >
          <el-icon :size="14"><RefreshRight /></el-icon>
          <span>重新生成</span>
        </button>
      </footer>
    </div>
  </article>
</template>

<style scoped lang="scss">
.msg {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 0;
}

// 用户消息整体右对齐:头像在右、内容在左
.msg--user {
  flex-direction: row-reverse;

  .msg__main {
    align-items: flex-end;
  }

  .msg__meta {
    flex-direction: row-reverse;
  }
}

.msg__avatar {
  width: 32px;
  height: 32px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 700;
  user-select: none;
}

.msg__avatar--agent {
  color: #fff;
  background: linear-gradient(145deg, #5b90f7 0%, var(--wo-brand) 60%, #1e56cf 100%);
  box-shadow: 0 2px 6px rgba(47, 111, 237, 0.26);
}

.msg__avatar--user {
  color: var(--wo-brand-hover);
  background: var(--wo-brand-wash-2);
  box-shadow: inset 0 0 0 1px var(--wo-hairline-brand);
}

.msg__main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
}

.msg__meta {
  display: flex;
  align-items: baseline;
  gap: 8px;
  margin-bottom: 5px;

  b {
    font-size: 12.5px;
    font-weight: 600;
    color: var(--wo-ink-2);
  }

  time {
    font-size: 11px;
    color: var(--wo-ink-4);
  }
}

// 用户气泡:淡蓝底 + 主色细边,比实色块更贴合本项目的「淡」。
.msg__bubble {
  max-width: 76%;
  padding: 9px 14px;
  border: 1px solid var(--wo-hairline-brand);
  border-radius: var(--wo-radius-lg) var(--wo-radius-lg) 4px var(--wo-radius-lg);
  background: var(--wo-brand-wash);
  color: var(--wo-ink-1);
  font-size: 14.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

// 内联编辑器:与气泡同宽、同色,编辑时视觉位置不跳
.msg__editor {
  width: min(76%, 620px);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.msg__editor-input {
  width: 100%;
  box-sizing: border-box;
  padding: 9px 14px;
  border: 1px solid var(--wo-hairline-brand);
  border-radius: var(--wo-radius-lg);
  background: var(--wo-surface);
  color: var(--wo-ink-1);
  font-family: inherit;
  font-size: 14.5px;
  line-height: 1.7;
  resize: vertical;
  min-height: 76px;

  &:focus {
    outline: none;
    border-color: var(--wo-brand);
    box-shadow: 0 0 0 3px var(--wo-brand-wash);
  }
}

.msg__editor-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 8px;
}

.msg__editor-count {
  margin-right: auto;
  font-size: 11px;
  color: var(--wo-ink-4);
}

.msg__editor-btn {
  padding: 5px 12px;
  border: 1px solid var(--wo-hairline);
  border-radius: var(--wo-radius-sm);
  background: var(--wo-surface);
  color: var(--wo-ink-2);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition:
    color var(--wo-dur-fast) var(--wo-ease),
    border-color var(--wo-dur-fast) var(--wo-ease),
    background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover:not(:disabled) {
    color: var(--wo-brand);
    border-color: var(--wo-hairline-brand);
  }

  &:disabled {
    opacity: 0.5;
    cursor: not-allowed;
  }
}

.msg__editor-btn--primary {
  border-color: var(--wo-brand);
  background: var(--wo-brand);
  color: #fff;

  &:hover:not(:disabled) {
    color: #fff;
    background: var(--wo-brand-hover);
    border-color: var(--wo-brand-hover);
  }
}

.msg__answer {
  width: 100%;
  min-width: 0;
}

// ---- 等待三点 ----
.thinking {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 2px;

  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background: var(--wo-ink-4);
    animation: thinking-bounce 1.2s infinite ease-in-out;

    &:nth-child(2) {
      animation-delay: 0.15s;
    }

    &:nth-child(3) {
      animation-delay: 0.3s;
    }
  }
}

@keyframes thinking-bounce {
  0%,
  60%,
  100% {
    transform: translateY(0);
    opacity: 0.5;
  }
  30% {
    transform: translateY(-3px);
    opacity: 1;
  }
}

// ---- 错误 / 停止提示条 ----
.msg__notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 10px;
  padding: 7px 12px;
  border-radius: var(--wo-radius-sm);
  background: var(--wo-surface-sunken);
  color: var(--wo-ink-3);
  font-size: 12px;
}

.msg__notice--error {
  background: var(--wo-st-5-bg);
  color: var(--wo-st-5-fg);
}

.msg__notice-btn {
  flex: none;
  padding: 2px 8px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: inherit;
  font-family: inherit;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  transition: background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: rgba(255, 255, 255, 0.55);
  }
}

// ---- 操作行 ----
.msg__actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: 6px;
  opacity: 0;
  transition: opacity var(--wo-dur-fast) var(--wo-ease);

  button {
    display: inline-flex;
    align-items: center;
    gap: 4px;
    padding: 3px 7px;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--wo-ink-4);
    font-family: inherit;
    font-size: 11.5px;
    cursor: pointer;
    transition:
      color var(--wo-dur-fast) var(--wo-ease),
      background-color var(--wo-dur-fast) var(--wo-ease);

    &:hover {
      color: var(--wo-brand);
      background: var(--wo-brand-wash);
    }
  }
}

.msg:hover .msg__actions,
.msg:focus-within .msg__actions {
  opacity: 1;
}

// 触屏没有 hover,操作行常驻
@media (hover: none) {
  .msg__actions {
    opacity: 1;
  }
}
</style>
