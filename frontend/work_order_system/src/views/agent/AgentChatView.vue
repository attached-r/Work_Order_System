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
import type { Component } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  Back,
  Bottom,
  CircleCheck,
  DocumentAdd,
  Finished,
  MagicStick,
  Menu,
  Plus,
  Promotion,
  Search,
  Share,
  Stamp,
  SwitchButton,
  Tickets,
  Timer,
  Warning,
} from '@element-plus/icons-vue'
import ConversationRail from '@/components/agent/ConversationRail.vue'
import ChatMessageItem from '@/components/agent/ChatMessageItem.vue'
import ChatComposer from '@/components/agent/ChatComposer.vue'
import {
  WRITE_CONFIRM_KEYWORD,
  getWritableActions,
} from '@/constants/agent'
import type { AgentWriteAction, AgentWriteActionKey } from '@/constants/agent'
import { useAgentStore } from '@/stores/agent'
import type { Conversation } from '@/stores/agent'
import { useUserStore } from '@/stores/user'
import { copyText } from '@/utils/clipboard'
import { exportConversationAsMarkdown } from '@/utils/exportChat'
import { extractWritePreview } from '@/utils/writePreview'
import type { WritePreviewInfo } from '@/utils/writePreview'
import { resolveWorkOrderIdByNo } from '@/api'
import { MAX_MESSAGE_LENGTH } from '@/api/agent'
import type { WorkOrderRef } from '@/utils/workOrderRef'

const agentStore = useAgentStore()
const userStore = useUserStore()
const route = useRoute()
const router = useRouter()

/** 解析 orderNo 期间防重入:双击同一条链接只发一次查询 */
const resolvingWorkOrder = ref(false)

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

// ---------------------------------------------------------------------------
// M4：写操作能力（只用于界面显隐，真正的判权在 agent 与项目一）
// ---------------------------------------------------------------------------

/** 当前账号被授权、且 agent 已开放的写动作（按固定顺序） */
const writableActions = computed<AgentWriteAction[]>(() =>
  getWritableActions(userStore.perms),
)

const canWrite = computed(() => writableActions.value.length > 0)

/**
 * 头部权限标识。
 *
 * M4 的写工具并不要求 workorder:query：只有派单权/审核权的账号，
 * 仍然可以在对话里执行动作，只是看不到工单详情。原来的「仅通用问答」
 * 在这种账号上是错误的，必须区分开。
 */
const permissionChip = computed<{ text: string; title: string } | null>(() => {
  if (!isLogged.value) return null

  if (!canQuery.value && canWrite.value) {
    return {
      text: '仅工单操作',
      title: '当前账号没有 workorder:query，但可以执行已授权的工单写操作',
    }
  }

  if (!canQuery.value) {
    return {
      text: '仅通用问答',
      title: '当前账号缺少 workorder:query，助手无法查询工单数据',
    }
  }

  return null
})

/** 空态文案：有写权限时明确说明「先预览、再确认」 */
const emptyDescription = computed(() =>
  canWrite.value
    ? '我可以查询工单、解释流程规范，也能在二次确认后执行审核、派单、转派、处理、验收与撤回。'
    : '我可以查询工单、解释流程规范,也能从知识库中找出操作依据。',
)

/** 空态警告：未登录 / 无查询权限 / 无查询但有写权限，三种情况文案不同 */
const emptyWarn = computed(() => {
  if (!isLogged.value) return '未登录,对话可以继续,但无法查询工单数据。'
  if (!canQuery.value && canWrite.value) {
    return '当前账号没有工单查询权限(workorder:query)，但可以在对话中执行已授权的工单动作。'
  }
  if (!canQuery.value) {
    return '当前账号缺少工单查询权限(workorder:query),只能回答通用流程问题。'
  }
  return ''
})

/** 写动作卡片图标：与详情页的动作语义一一对应 */
const WRITE_ICONS: Record<AgentWriteActionKey, Component> = {
  review: CircleCheck,
  dispatch: Promotion,
  transfer: SwitchButton,
  process: Finished,
  accept: Stamp,
  withdraw: Back,
}

/** 最后一条用户消息的下标:只有它能编辑后重发 */
const lastUserIndex = computed(() => {
  for (let i = messages.value.length - 1; i >= 0; i -= 1) {
    if (messages.value[i]?.role === 'user') return i
  }
  return -1
})

/**
 * 最后一条助手回答是否是一个「等待确认的写操作预览」。
 *
 * 只在满足全部条件时返回：
 *   - 用户拥有至少一个写权限（否则 agent 那边也不会签发令牌）；
 *   - 当前没有流式输出（避免半截文本误判）；
 *   - 最后一条消息是正常结束的 assistant。
 *
 * 识别逻辑见 utils/writePreview.ts：mock 模式解析 PREVIEW JSON，
 * 真实模型模式退化为「是否询问确认执行」的话术识别。
 */
const writePreview = computed<WritePreviewInfo | null>(() => {
  if (!canWrite.value || streaming.value) return null

  const last = messages.value[messages.value.length - 1]
  if (!last || last.role !== 'assistant' || last.status !== 'done') return null

  return extractWritePreview(last.content)
})

/**
 * 建议追问:只在「最后一条是正常完成的回答」时出现。
 * 流式进行中、报错、手动停止都不打扰用户。
 */
const followUps = computed<string[]>(() => {
  const last = messages.value[messages.value.length - 1]
  if (!last || last.role !== 'assistant' || last.status !== 'done' || !last.content) return []
  if (streaming.value) return []

  const list: string[] = []
  // 没有工单查询权限的账号不展示数据类追问,免得问了也查不到
  if (canQuery.value) list.push('我的待处理工单有哪些?')
  list.push('被驳回后如何修改并重新提交?', '工单超时了怎么处理?')
  return list
})

/** 空态建议:只读类问题,点一下直接发问 */
interface ReadSuggestion {
  title: string
  prompt: string
  icon: Component
  /** 需要 workorder:query 才能得到有效回答 */
  requiresQuery?: boolean
}

const READ_SUGGESTIONS: ReadSuggestion[] = [
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
    requiresQuery: true,
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

/** 无查询权限时不展示「帮我查询…」，免得点进去只能得到 fail-closed 提示 */
const suggestions = computed(() =>
  READ_SUGGESTIONS.filter((item) => !item.requiresQuery || canQuery.value),
)

/** 输入框提示：有写权限时示例直接给出写操作句式 */
const composerPlaceholder = computed(() =>
  canWrite.value
    ? '告诉我工单 ID 和要执行的动作,例如:审核通过工单 123'
    : '向工单 Agent 提问,例如:审核驳回后怎么重新提交?',
)

/** 输入框下方提示：强调「先预览、再确认」 */
const composerNote = computed(() =>
  canWrite.value
    ? '写操作会先给出预览，点击「确认执行」后才会提交；回答由 AI 生成，请核对。'
    : '回答由 AI 生成,请结合工单实际情况核对。',
)

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
  // 追问 chip 是在回答结束后才出现的,把数量纳入签名,出现时也能自动贴底
  return `${list.length}:${last?.content.length ?? 0}:${last?.status ?? ''}:${followUps.value.length}`
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

function handleRename(id: string, title: string): void {
  agentStore.renameConversation(id, title)
}

function handleExport(conversation: Conversation): void {
  try {
    exportConversationAsMarkdown(conversation)
    ElMessage.success('已导出 Markdown')
  } catch {
    ElMessage.error('导出失败,请稍后重试')
  }
}

function handleClearAll(): void {
  agentStore.clearAllConversations()
  pinned.value = true
  showJump.value = false
  void nextTick(settleScroll)
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

/**
 * M4：确认执行当前预览。
 *
 * 只发送约定关键词，不携带令牌、也不直接调写接口 —— 令牌在 agent 进程内，
 * 由它根据 sessionId 找到预览并完成提交。真实模型路径同样适用：
 * 模型会把这句话理解为「用户同意」，然后带令牌再次调用写工具。
 */
function handleConfirmWrite(): void {
  if (streaming.value) return
  handleSend(WRITE_CONFIRM_KEYWORD)
}

/**
 * 写操作卡片只预填、不发送。
 *
 * 写操作必须由用户明确指定目标工单；预填内容刻意不带示例 ID，
 * 避免用户直接回车把动作落到一张错误的单上。
 */
function prefillWrite(action: AgentWriteAction): void {
  if (streaming.value || !isLogged.value) return

  draft.value = action.prompt.slice(0, MAX_MESSAGE_LENGTH)
  ElMessage.info('已填入输入框，请补充工单 ID 等信息后发送')
  void nextTick(() => composerRef.value?.focus())
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

/** 编辑最后一条用户消息并重发;store 会做最终校验 */
function handleEdit(messageId: string, text: string): void {
  const accepted = agentStore.editAndResend(messageId, text)
  if (!accepted) {
    ElMessage.warning('这条消息无法编辑重发,请确认助手没有正在生成')
    return
  }
  pinned.value = true
  void nextTick(() => scrollToBottom('auto'))
}

/**
 * 点击回答里的工单引用。
 *
 * 数字 ID 可以直接跳;展示编号必须先反查内部 id。
 * 权限在这里再挡一层:即使链接被渲染出来,没有 workorder:query 也不发起查询。
 */
async function handleOpenWorkOrder(ref: WorkOrderRef): Promise<void> {
  if (!canQuery.value) {
    ElMessage.warning('当前账号没有工单查询权限')
    return
  }
  if (resolvingWorkOrder.value) return

  if (ref.id !== undefined) {
    if (!Number.isSafeInteger(ref.id) || ref.id <= 0) return
    void router.push({ name: 'workorder-detail', params: { id: ref.id } })
    return
  }

  if (!ref.orderNo) return

  resolvingWorkOrder.value = true
  try {
    const id = await resolveWorkOrderIdByNo(ref.orderNo)
    if (id === null) {
      ElMessage.warning(`未找到工单 ${ref.orderNo},可能不在你的数据范围内`)
      return
    }
    void router.push({ name: 'workorder-detail', params: { id } })
  } catch (error) {
    ElMessage.error(error instanceof Error ? error.message : '打开工单失败,请稍后重试')
  } finally {
    resolvingWorkOrder.value = false
  }
}

function handleFollowUp(prompt: string): void {
  handleSend(prompt)
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

/**
 * 消费工单详情页带来的 `?ask=` 预填。
 *
 * 只写入草稿、不自动发送,用户可以改完再问。
 * 处理完立刻用 replace 清掉参数:刷新或后退都不该再次覆盖当前草稿。
 */
function consumeAskQuery(): void {
  const raw = route.query.ask
  const ask = Array.isArray(raw) ? raw[0] : raw
  if (typeof ask !== 'string' || !ask.trim()) return

  // 截到发送上限,避免预填的内容 store 直接拒收
  draft.value = ask.slice(0, MAX_MESSAGE_LENGTH)
  void nextTick(() => composerRef.value?.focus())

  const nextQuery = { ...route.query }
  delete nextQuery.ask
  void router.replace({ name: 'agent-chat', query: nextQuery })
}

// 已经停留在 /agent 时再次点「问助手」,路由不变但 query 会更新
watch(
  () => route.query.ask,
  () => consumeAskQuery(),
)

/**
 * 全局快捷键:
 *   Esc       —— 先关移动端抽屉;没有抽屉时停止生成
 *   Ctrl/Cmd+K —— 新建对话
 *
 * 输入法组合中、消息框正在编辑(已 stopPropagation)、弹窗打开时都不响应。
 */
function handleKeydown(event: KeyboardEvent): void {
  if (event.defaultPrevented || event.isComposing) return
  if (event.target instanceof Element && event.target.closest('.el-overlay')) return

  if ((event.ctrlKey || event.metaKey) && !event.altKey && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    handleNewChat()
    return
  }

  if (event.key !== 'Escape') return
  if (railOpen.value) {
    railOpen.value = false
    return
  }
  if (streaming.value) agentStore.stop()
}

onMounted(() => {
  window.addEventListener('keydown', handleKeydown)
  consumeAskQuery()
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
        @rename="handleRename"
        @export="handleExport"
        @clear="handleClearAll"
        @close="railOpen = false"
      />
    </div>

    <div v-if="railOpen" class="chat__backdrop" @click="railOpen = false"></div>

    <section class="chat__main">
      <header class="chat__head">
        <!-- 页头内容与消息列同宽，避免标题贴在左侧、对话却在中间 -->
        <div class="chat__head-inner">
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
              v-if="permissionChip"
              class="chat__perm-chip"
              :title="permissionChip.title"
            >
              {{ permissionChip.text }}
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
                :is-last-user="index === lastUserIndex"
                :busy="streaming"
                :enable-work-order-links="canQuery"
                :write-preview="writePreview"
                :can-open-preview-order="canQuery"
                @copy="handleCopy"
                @regenerate="handleRegenerate"
                @retry="handleRetry"
                @edit="handleEdit"
                @open-workorder="handleOpenWorkOrder"
                @confirm-write="handleConfirmWrite"
              />

              <!-- 建议追问:只在最后一条回答正常结束后出现 -->
              <div v-if="followUps.length" class="chat-followups">
                <span class="chat-followups__label">继续追问</span>
                <button
                  v-for="item in followUps"
                  :key="item"
                  type="button"
                  class="chat-followups__chip"
                  :disabled="streaming || !isLogged"
                  @click="handleFollowUp(item)"
                >
                  {{ item }}
                </button>
              </div>
            </template>

            <!-- 空态:欢迎 + 建议问题 -->
            <div v-else class="chat-empty">
              <span class="chat-empty__mark">
                <el-icon :size="24"><MagicStick /></el-icon>
              </span>

              <p class="wo-eyebrow">Work Order Agent</p>
              <h2>有什么可以帮你?</h2>
              <p class="chat-empty__desc">{{ emptyDescription }}</p>

              <p v-if="emptyWarn" class="chat-empty__warn">{{ emptyWarn }}</p>

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

              <!--
                M4：按权限展示可执行的写动作。
                点击只预填输入框 —— 写操作必须由用户补全工单 ID 后主动发送。
              -->
              <template v-if="writableActions.length">
                <div class="chat-empty__section">
                  <span class="chat-empty__section-title">工单操作</span>
                  <span class="chat-empty__section-badge">需二次确认</span>
                </div>

                <div class="chat-empty__write-grid">
                  <button
                    v-for="action in writableActions"
                    :key="action.key"
                    type="button"
                    class="chat-empty__write-card"
                    :disabled="streaming || !isLogged"
                    @click="prefillWrite(action)"
                  >
                    <span class="chat-empty__write-icon">
                      <el-icon :size="15"><component :is="WRITE_ICONS[action.key]" /></el-icon>
                    </span>

                    <span class="chat-empty__write-body">
                      <span class="chat-empty__write-label">{{ action.label }}</span>
                      <span class="chat-empty__write-hint">{{ action.hint }}</span>
                    </span>

                    <span class="chat-empty__write-pre">{{ action.precondition }}</span>
                  </button>
                </div>

                <p class="chat-empty__write-note">
                  点击只会填入输入框；补充工单 ID 后发送，助手会先给出预览，确认后才真正提交。
                </p>
              </template>
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
          :placeholder="composerPlaceholder"
          :note="composerNote"
          @send="handleSend"
          @stop="agentStore.stop()"
        />
      </footer>
    </section>
  </div>
</template>

<style scoped lang="scss">
.chat {
  // 对话列的统一宽度：消息区、输入区共用。
  // 用 max-width + margin auto，而不是固定 width —— 窄屏时跟随可用宽度收缩，
  // 宽屏时也不会把一行文字拉得过长。
  --chat-column: 1040px;

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
  border-bottom: 1px solid var(--wo-hairline);
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(10px) saturate(1.2);
}

// 与 .chat__thread 完全同构：同一个 max-width、同一种左右 padding，
// 页头标题才会和下方的消息正文对齐。
.chat__head-inner {
  width: 100%;
  max-width: var(--chat-column);
  margin-inline: auto;
  padding: 0 clamp(16px, 4vw, 44px);
  display: flex;
  align-items: center;
  gap: 10px;
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
  // width:100% + max-width + auto margin：在右侧主列里水平居中，
  // 窗口变宽时只放大到 --chat-column 就停住，两侧留白始终相等。
  width: 100%;
  max-width: var(--chat-column);
  margin-inline: auto;
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
// 建议追问
// ---------------------------------------------------------------------------
.chat-followups {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  // 与助手正文左对齐(头像 32px + 间距 12px)
  padding: 2px 0 6px 44px;
}

.chat-followups__label {
  font-size: 11px;
  color: var(--wo-ink-4);
}

.chat-followups__chip {
  padding: 5px 12px;
  border: 1px solid var(--wo-hairline);
  border-radius: 999px;
  background: var(--wo-surface);
  color: var(--wo-ink-2);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition:
    color var(--wo-dur-fast) var(--wo-ease),
    border-color var(--wo-dur-fast) var(--wo-ease),
    background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover:not(:disabled) {
    color: var(--wo-brand);
    border-color: var(--wo-hairline-brand);
    background: var(--wo-brand-wash);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
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

// ---------------------------------------------------------------------------
// M4：工单操作（写）入口
// ---------------------------------------------------------------------------
.chat-empty__section {
  width: 100%;
  margin-top: 24px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.chat-empty__section-title {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--wo-ink-2);
}

.chat-empty__section-badge {
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--wo-st-0-bg);
  color: var(--wo-st-0-fg);
  font-size: 10.5px;
  font-weight: 600;
}

.chat-empty__write-grid {
  width: 100%;
  margin-top: 10px;
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
  text-align: left;
}

.chat-empty__write-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
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

.chat-empty__write-icon {
  width: 28px;
  height: 28px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 8px;
  background: var(--wo-brand-wash);
  color: var(--wo-brand);
}

.chat-empty__write-body {
  min-width: 0;
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 1px;
}

.chat-empty__write-label {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wo-ink-1);
}

.chat-empty__write-hint {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 11px;
  color: var(--wo-ink-4);
}

.chat-empty__write-pre {
  flex: none;
  align-self: flex-start;
  max-width: 96px;
  padding: 1px 6px;
  border-radius: 4px;
  background: var(--wo-surface-sunken);
  color: var(--wo-ink-4);
  font-size: 10.5px;
  line-height: 1.5;
  text-align: right;
}

.chat-empty__write-note {
  width: 100%;
  margin-top: 9px;
  text-align: left;
  font-size: 11px;
  line-height: 1.6;
  color: var(--wo-ink-4);
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

  // 输入框与消息列共用同一个宽度和同一套左右内边距，
  // 这样输入框外框的左/右边缘与消息区对齐，不会出现「输入框比回答更宽」的错位感。
  > * {
    width: 100%;
    max-width: var(--chat-column);
    margin-inline: auto;
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

  .chat__head-inner {
    padding: 0 12px;
  }

  .chat-empty__grid,
  .chat-empty__write-grid {
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
