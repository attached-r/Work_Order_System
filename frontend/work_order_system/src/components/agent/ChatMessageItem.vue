<script setup lang="ts">
/**
 * 单条消息
 *
 * 用户消息走纯文本气泡(不做 Markdown),助手消息走 Markdown。
 * 这样既能避免用户输入里的 `*` / `#` 被误渲染,也让两类消息在视觉上区分得开。
 */
import { computed } from 'vue'
import { CopyDocument, MagicStick, RefreshRight } from '@element-plus/icons-vue'
import AgentMarkdown from './AgentMarkdown.vue'
import { useUserStore } from '@/stores/user'
import { formatClock } from '@/utils/datetime'
import type { ChatMessage } from '@/stores/agent'

const props = defineProps<{
  message: ChatMessage
  /** 是否是当前最后一条 assistant 消息(只有它可以重新生成) */
  isLastAssistant: boolean
  /** 任意一条消息正在流式输出(此时禁用重新生成,避免并发) */
  busy: boolean
}>()

const emit = defineEmits<{
  copy: [text: string]
  regenerate: []
  retry: []
}>()

const userStore = useUserStore()

const isUser = computed(() => props.message.role === 'user')
const isStreaming = computed(() => props.message.status === 'streaming')
const time = computed(() => formatClock(new Date(props.message.createdAt).toISOString()))

/** 错误/停止状态需要额外的提示条;正常结束不显示 */
const showError = computed(() => props.message.status === 'error')
const showStopped = computed(() => props.message.status === 'stopped')
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

      <!-- 用户:纯文本气泡 -->
      <div v-if="isUser" class="msg__bubble">{{ message.content }}</div>

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
      </div>

      <!-- 操作行:平时低对比,悬浮/聚焦才显形 -->
      <footer v-if="message.content" class="msg__actions">
        <button type="button" title="复制" @click="emit('copy', message.content)">
          <el-icon :size="14"><CopyDocument /></el-icon>
          <span>复制</span>
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
