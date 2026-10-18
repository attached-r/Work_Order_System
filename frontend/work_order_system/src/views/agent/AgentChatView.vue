<script setup lang="ts">
/**
 * 智能助手
 *
 * 页面只做三件事:
 *   1. 把 store 里的会话/消息摆成 ChatGPT 式布局;
 *   2. 管理滚动位置(贴底跟随、上翻不抢滚动、回到底部);
 *   3. 把用户动作转成 store 调用,自己不做流式解析。
 *
 * 高度链路:BasicLayout 的 fullBleed 模式让本页撑满剩余空间,
 * 页面内部只有消息区滚动,页头和输入区固定。
 */
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Bottom,
  DocumentAdd,
  MagicStick,
  Menu,
  Plus,
  Search,
  Share,
  Tickets,
  Timer,
  Warning,
} from '@element-plus/icons-vue'
import ConversationRail from '@/components/agent/ConversationRail.vue'
import ChatMessageItem from '@/components/agent/ChatMessageItem.vue'
import ChatComposer from '@/components/agent/ChatComposer.vue'
import { useAgentStore } from '@/stores/agent'
import { useUserStore } from '@/stores/user'
import { copyText } from '@/utils/clipboard'

const agentStore = useAgentStore()
const userStore = useUserStore()

/** 输入框草稿;真正的发送由 ChatComposer 通过 send 事件交出来 */
const draft = ref('')

/** 移动端会话抽屉;桌面端会话栏常驻,这个状态不用 */
const railOpen = ref(false)

const scrollEl = ref<HTMLElement | null>(null)
const composerRef = ref<InstanceType<typeof ChatComposer> | null>(null)

/** 用户是否停在底部附近:只有这时流式内容才自动跟随 */
const pinned = ref(true)
const showJump = ref(false)

/** 距底部小于这个值就认为「贴着底」 */
const NEAR_BOTTOM = 80
/** 滚开这么远才显示「回到底部」,避免轻微滚动就冒按钮 */
const JUMP_THRESHOLD = 160

const messages = computed(() => agentStore.activeMessages)
const streaming = computed(() => agentStore.streaming)
const activeId = computed(() => agentStore.activeId)
const conversations = computed(() => agentStore.conversations)
const activeTitle = computed(() => agentStore.activeConversation?.title ?? '新对话')
const isLogged = computed(() => userStore.isLogged)
const canQuery = computed(() => userStore.hasPerm('workorder:query'))

/** 空态建议:全部围绕工单域,点一下直接发问 */
const suggestions = [
  {
    title: '工单状态流转',
    prompt: '工单从提交到完成要经过哪些状态?',
    icon: Tickets,
  },
  {
    title: '审核驳回处理',
    prompt: '工单被审核驳回后应该怎么处理、怎么重新提交?',
    icon: Warning,
  },
  {
    title: '查询待处理工单',
    prompt: '帮我查询当前待处理的工单有哪些。',
    icon: Search,
  },
  {
    title: '超时与 SLA 规则',
    prompt: '工单超时是怎么判定的?SLA 规则是什么?',
    icon: Timer,
  },
  {
    title: '提交工单',
    prompt: '提交工单需要填写哪些信息?有什么注意事项?',
    icon: DocumentAdd,
  },
  {
    title: '派单与转派',
    prompt: '派单和转派有什么区别?分别由谁操作?',
    icon: Share,
  },
]

// ---------------------------------------------------------------------------
// 滚动
// ---------------------------------------------------------------------------

function distanceFromBottom(): number {
  const el = scrollEl.value
  if (!el) return 0
  return el.scrollHeight - el.scrollTop - el.clientHeight
}

function scrollToBottom(behavior: ScrollBehavior = 'smooth'): void {
  const el = scrollEl.value
  if (!el) return
  el.scrollTo({ top: el.scrollHeight, behavior })
}

/**
 * 进入会话时的落点:
 *   - 有消息:直接看最新一条;
 *   - 空会话:停在顶部,别把欢迎图标顶出视口。
 */
function settleScroll(): void {
  const el = scrollEl.value
  if (!el) return
  if (messages.value.length) scrollToBottom('auto')
  else el.scrollTo({ top: 0 })
}

function handleScroll(): void {
  const distance = distanceFromBottom()
  pinned.value = distance < NEAR_BOTTOM
  showJump.value = distance > JUMP_THRESHOLD
}

/**
 * 流式期间内容每个刷新周期都会变长。
 * 用一个轻量的「尾部签名」做 watch,而不是深监听整个消息数组 ——
 * 后者在长对话里会反复遍历所有消息。
 */
const tailSignature = computed(() => {
  const list = messages.value
  const last = list[list.length - 1]
  return `${list.length}:${last?.content.length ?? 0}:${last?.status ?? ''}`
})

watch(
  tailSignature,
  () => {
    if (!pinned.value) return
    void nextTick(() => scrollToBottom('auto'))
  },
  { flush: 'post' },
)

// 切换会话:回到该会话合适的落点,并收起移动端抽屉
watch(activeId, () => {
  pinned.value = true
  showJump.value = false
  railOpen.value = false
  void nextTick(settleScroll)
})

// ---------------------------------------------------------------------------
// 会话操作
// ---------------------------------------------------------------------------

function handleNewChat(): void {
  agentStore.newChat()
  railOpen.value = false
  pinned.value = true
  void nextTick(() => {
    // 新会话一定是空的,停在顶部
    settleScroll()
    composerRef.value?.focus()
  })
}

function handleSelect(id: string): void {
  agentStore.selectConversation(id)
  railOpen.value = false
}

function handleDelete(id: string): void {
  agentStore.deleteConversation(id)
}

// ---------------------------------------------------------------------------
// 消息操作
// ---------------------------------------------------------------------------

function handleSend(text: string): void {
  // 路由守卫正常情况下不会让未登录用户进来,这里只做防御
  if (!isLogged.value) {
    ElMessage.warning('请先登录后再使用智能助手')
    return
  }

  const accepted = agentStore.send(text)
  if (!accepted) {
    ElMessage.warning('消息为空、过长,或助手正在生成中')
    return
  }

  pinned.value = true
  railOpen.value = false
  void nextTick(() => scrollToBottom('auto'))
}

function sendSuggestion(prompt: string): void {
  handleSend(prompt)
}

async function handleCopy(text: string): Promise<void> {
  const ok = await copyText(text)
  if (ok) ElMessage.success('已复制')
  else ElMessage.error('复制失败,请手动选择文本')
}

function handleRegenerate(): void {
  agentStore.regenerateLast()
}

function handleRetry(): void {
  agentStore.retryLast()
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

/** Esc 关闭移动端抽屉 */
function handleKeydown(event: KeyboardEvent): void {
  if (event.key === 'Escape') railOpen.value = false
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  void nextTick(settleScroll)
})

onUnmounted(() => {
  window.removeEventListener('keydown', handleKeydown)
  // 离开页面立即中止流,避免后台继续读流、也避免返回时状态错乱。
  // 已输出的部分由 store 的 finally 保留并落盘。
  agentStore.stop()
})
</script>

<template>
  <div class="chat">
    <!-- 会话栏:桌面常驻,窄屏变抽屉 -->
    <div class="chat__rail-wrap" :class="{ 'is-open': railOpen }">
      <ConversationRail
        :conversations="conversations"
        :active-id="activeId"
        :streaming-conversation-id="agentStore.streamingConversationId"
        @select="handleSelect"
        @new="handleNewChat"
        @delete="handleDelete"
        @close="railOpen = false"
      />
    </div>

    <div v-if="railOpen" class="chat__backdrop" @click="railOpen = false"></div>

    <section class="chat__main">
      <header class="chat__head">
        <button
          type="button"
          class="chat__icon-btn chat__rail-toggle"
          title="对话记录"
          @click="railOpen = true"
        >
          <el-icon :size="17"><Menu /></el-icon>
        </button>

        <div class="chat__title">
          <span class="wo-eyebrow">Agent</span>
          <b>{{ activeTitle }}</b>
        </div>

        <div class="chat__head-right">
          <span
            v-if="isLogged && !canQuery"
            class="chat__perm-chip"
            title="当前账号缺少 workorder:query,助手无法查询工单数据"
          >
            仅通用问答
          </span>

          <button
            type="button"
            class="chat__icon-btn chat__new-icon"
            title="新建对话"
            @click="handleNewChat"
          >
            <el-icon :size="17"><Plus /></el-icon>
          </button>
        </div>
      </header>

      <div class="chat__viewport">
        <div ref="scrollEl" class="chat__scroll" @scroll.passive="handleScroll">
          <div class="chat__thread">
            <!-- 有消息:正常对话流 -->
            <template v-if="messages.length">
              <ChatMessageItem
                v-for="(message, index) in messages"
                :key="message.id"
                :message="message"
                :is-last-assistant="index === messages.length - 1 && message.role === 'assistant'"
                :busy="streaming"
                @copy="handleCopy"
                @regenerate="handleRegenerate"
                @retry="handleRetry"
              />
            </template>

            <!-- 空态:欢迎 + 建议问题 -->
            <div v-else class="chat-empty">
              <span class="chat-empty__mark">
                <el-icon :size="24"><MagicStick /></el-icon>
              </span>

              <p class="wo-eyebrow">Work Order Agent</p>
              <h2>有什么可以帮你?</h2>
              <p class="chat-empty__desc">
                我可以查询工单、解释流程规范,也能从知识库中找出操作依据。
              </p>

              <p v-if="!isLogged" class="chat-empty__warn">
                未登录,对话可以继续,但无法查询工单数据。
              </p>
              <p v-else-if="!canQuery" class="chat-empty__warn">
                当前账号缺少工单查询权限(workorder:query),只能回答通用流程问题。
              </p>

              <div class="chat-empty__grid">
                <button
                  v-for="item in suggestions"
                  :key="item.title"
                  type="button"
                  class="chat-empty__card"
                  :disabled="streaming || !isLogged"
                  @click="sendSuggestion(item.prompt)"
                >
                  <span class="chat-empty__icon">
                    <el-icon :size="15"><component :is="item.icon" /></el-icon>
                  </span>
                  <span class="chat-empty__label">{{ item.title }}</span>
                  <span class="chat-empty__prompt">{{ item.prompt }}</span>
                </button>
              </div>
            </div>
          </div>
        </div>

        <!-- 用户上翻阅读时不抢滚动,给一个显式的回底按钮 -->
        <Transition name="jump">
          <button
            v-if="showJump"
            type="button"
            class="chat__jump"
            @click="scrollToBottom('smooth')"
          >
            <el-icon :size="14"><Bottom /></el-icon>
            <span>回到底部</span>
          </button>
        </Transition>
      </div>

      <footer class="chat__composer">
        <ChatComposer
          ref="composerRef"
          v-model="draft"
          :streaming="streaming"
          :disabled="!isLogged"
          @send="handleSend"
          @stop="agentStore.stop()"
        />
      </footer>
    </section>
  </div>
</template>

<style scoped lang="scss">
.chat {
  position: relative;
  flex: 1;
  min-height: 0;
  display: flex;
  overflow: hidden;
  background: var(--wo-canvas);
}

// ---------------------------------------------------------------------------
// 会话栏
// ---------------------------------------------------------------------------
.chat__rail-wrap {
  flex: none;
  width: 268px;
  min-height: 0;
  display: flex;

  > * {
    width: 100%;
  }
}

.chat__backdrop {
  position: absolute;
  inset: 0;
  z-index: 20;
  background: rgba(22, 32, 46, 0.28);
  backdrop-filter: blur(2px);
}

// ---------------------------------------------------------------------------
// 主列
// ---------------------------------------------------------------------------
.chat__main {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
}

.chat__head {
  height: 58px;
  flex: none;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 0 18px;
  border-bottom: 1px solid var(--wo-hairline);
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(10px) saturate(1.2);
}

.chat__title {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  line-height: 1.25;

  b {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-size: 14px;
    font-weight: 700;
    color: var(--wo-ink-1);
  }
}

.chat__head-right {
  flex: none;
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat__perm-chip {
  padding: 3px 9px;
  border-radius: 999px;
  background: var(--wo-st-0-bg);
  color: var(--wo-st-0-fg);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
}

.chat__icon-btn {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  flex: none;
  border: none;
  border-radius: var(--wo-radius-sm);
  background: transparent;
  color: var(--wo-ink-2);
  cursor: pointer;
  transition:
    background-color var(--wo-dur-fast) var(--wo-ease),
    color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: var(--wo-brand-wash);
    color: var(--wo-brand-hover);
  }
}

// 桌面端会话栏常驻,这两个按钮只在窄屏出现
.chat__rail-toggle,
.chat__new-icon {
  display: none;
}

// ---------------------------------------------------------------------------
// 消息区
// ---------------------------------------------------------------------------
.chat__viewport {
  position: relative;
  flex: 1;
  min-height: 0;
  background: linear-gradient(180deg, #fbfdff 0%, var(--wo-canvas) 100%);
}

.chat__scroll {
  height: 100%;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.chat__thread {
  max-width: 860px;
  margin: 0 auto;
  padding: 18px clamp(16px, 4vw, 44px) 20px;
}

.chat__jump {
  position: absolute;
  right: 24px;
  bottom: 16px;
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 6px 12px;
  border: 1px solid var(--wo-hairline);
  border-radius: 999px;
  background: var(--wo-surface);
  color: var(--wo-ink-2);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  box-shadow: var(--wo-shadow-sm);
  transition:
    color var(--wo-dur-fast) var(--wo-ease),
    border-color var(--wo-dur-fast) var(--wo-ease),
    transform var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    color: var(--wo-brand);
    border-color: var(--wo-hairline-brand);
    transform: translateY(-1px);
  }
}

.jump-enter-active,
.jump-leave-active {
  transition:
    opacity var(--wo-dur-fast) var(--wo-ease),
    transform var(--wo-dur-fast) var(--wo-ease);
}

.jump-enter-from,
.jump-leave-to {
  opacity: 0;
  transform: translateY(4px);
}

// ---------------------------------------------------------------------------
// 空态
// ---------------------------------------------------------------------------
.chat-empty {
  max-width: 680px;
  margin: 0 auto;
  padding: clamp(20px, 7vh, 64px) 0 20px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.chat-empty__mark {
  width: 50px;
  height: 50px;
  display: grid;
  place-items: center;
  margin-bottom: 14px;
  border-radius: 15px;
  color: #fff;
  background: linear-gradient(145deg, #5b90f7 0%, var(--wo-brand) 58%, #1e56cf 100%);
  box-shadow: 0 6px 18px rgba(47, 111, 237, 0.26);
}

.chat-empty h2 {
  margin-top: 6px;
  font-size: clamp(22px, 2.6vw, 27px);
  font-weight: 700;
  letter-spacing: -0.02em;
}

.chat-empty__desc {
  margin-top: 8px;
  max-width: 52ch;
  font-size: 13px;
  line-height: 1.7;
  color: var(--wo-ink-3);
}

.chat-empty__warn {
  margin-top: 12px;
  padding: 5px 12px;
  border-radius: 999px;
  background: var(--wo-st-0-bg);
  color: var(--wo-st-0-fg);
  font-size: 11.5px;
}

.chat-empty__grid {
  width: 100%;
  margin-top: 26px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
  text-align: left;
}

.chat-empty__card {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 12px 14px;
  border: 1px solid var(--wo-hairline);
  border-radius: var(--wo-radius);
  background: var(--wo-surface);
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition:
    border-color var(--wo-dur-fast) var(--wo-ease),
    box-shadow var(--wo-dur-fast) var(--wo-ease),
    transform var(--wo-dur-fast) var(--wo-ease);

  &:hover:not(:disabled) {
    border-color: var(--wo-hairline-brand);
    box-shadow: var(--wo-shadow-sm);
    transform: translateY(-1px);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

.chat-empty__icon {
  width: 28px;
  height: 28px;
  display: grid;
  place-items: center;
  margin-bottom: 4px;
  border-radius: 8px;
  background: var(--wo-brand-wash);
  color: var(--wo-brand);
}

.chat-empty__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--wo-ink-1);
}

.chat-empty__prompt {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  font-size: 11.5px;
  line-height: 1.55;
  color: var(--wo-ink-4);
}

// ---------------------------------------------------------------------------
// 输入区
// ---------------------------------------------------------------------------
.chat__composer {
  flex: none;
  padding: 8px clamp(16px, 4vw, 44px) 14px;

  > * {
    max-width: 860px;
    margin: 0 auto;
  }
}

// ---------------------------------------------------------------------------
// 窄屏:会话栏变抽屉
// ---------------------------------------------------------------------------
@media (max-width: 960px) {
  .chat__rail-wrap {
    position: absolute;
    inset: 0 auto 0 0;
    z-index: 30;
    width: 280px;
    transform: translateX(-100%);
    transition: transform var(--wo-dur) var(--wo-ease);

    &.is-open {
      transform: translateX(0);
    }
  }

  .chat__rail-toggle,
  .chat__new-icon {
    display: grid;
  }
}

@media (max-width: 620px) {
  .chat__thread {
    padding-left: 14px;
    padding-right: 14px;
  }

  .chat__head {
    padding: 0 12px;
  }

  .chat-empty__grid {
    grid-template-columns: 1fr;
  }

  .chat-empty__prompt {
    -webkit-line-clamp: 1;
    line-clamp: 1;
  }

  .chat__composer {
    padding-left: 12px;
    padding-right: 12px;
  }

  .chat__jump {
    right: 14px;
  }
}
</style>
