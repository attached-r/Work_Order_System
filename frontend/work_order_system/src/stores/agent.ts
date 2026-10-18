/**
 * Agent 对话状态
 *
 * 为什么要前端自己存一份历史:
 *   Agent 的会话记忆只在它的进程内(每个 sessionId 最近 20 条),
 *   刷新页面或重启服务就没了。界面要能回看,所以展示记录落 localStorage。
 *
 * 两条关键的流式纪律:
 *   1. token 增量不直接写响应式状态 —— 用 requestAnimationFrame + 40ms 节流合并,
 *      否则每个 token 都会触发一次 Markdown 全量重解析;
 *   2. 落盘只在「发送开始 / 流结束 / 新建 / 删除」这些节点做,
 *      不能跟着 token 写 localStorage,否则既卡又容易撞配额。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { AGENT_ACTIVE_KEY, AGENT_CHAT_KEY } from '@/constants/storage'
import { MAX_MESSAGE_LENGTH, isAbortError, streamAgentChat } from '@/api/agent'

export type MessageStatus = 'done' | 'streaming' | 'error' | 'stopped'

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  createdAt: number
  status: MessageStatus
  error?: string | null
}

export interface Conversation {
  /** 同时作为发给 Agent 的 sessionId */
  id: string
  title: string
  createdAt: number
  updatedAt: number
  messages: ChatMessage[]
}

// ---------------------------------------------------------------------------
// 持久化参数
// ---------------------------------------------------------------------------

const STORAGE_VERSION = 1
/** 最多保留 20 个会话,超出的按更新时间淘汰 */
const MAX_CONVERSATIONS = 20
/** 每个会话最多保留 80 条消息,避免 localStorage 被长对话撑爆 */
const MAX_MESSAGES_PER_CONVERSATION = 80
/** 落盘时单条消息内容的上限;内存里仍保留完整内容 */
const MAX_STORED_CONTENT_CHARS = 20_000
/** 流式增量合并的最小间隔,约合 25fps,足够顺滑又不会把 CPU 打满 */
const MIN_FLUSH_INTERVAL_MS = 40
/** 新会话的占位标题 */
const DEFAULT_TITLE = '新对话'

/** 无会话时返回同一个空数组,避免每次计算都产生新引用导致视图反复刷新 */
const EMPTY_MESSAGES: ChatMessage[] = []

// ---------------------------------------------------------------------------
// 纯工具
// ---------------------------------------------------------------------------

/** 生成 id;优先用 crypto.randomUUID,不可用时退回时间戳 + 随机串 */
function newId(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return `${Date.now()}-${Math.random().toString(36).slice(2, 10)}`
}

/** 会话标题:压缩空白后按字符截断,避免把 emoji 的代理对劈开 */
function makeTitle(text: string): string {
  const chars = Array.from(text.replace(/\s+/g, ' ').trim())
  if (chars.length === 0) return DEFAULT_TITLE
  return chars.length > 24 ? `${chars.slice(0, 24).join('')}…` : chars.join('')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

/**
 * 反序列化一条消息。
 * 返回 null 表示这条记录不可信,直接丢弃,不让坏数据污染整个会话。
 */
function normalizeMessage(raw: unknown): ChatMessage | null {
  if (!isRecord(raw)) return null
  if (typeof raw.id !== 'string') return null
  if (raw.role !== 'user' && raw.role !== 'assistant') return null

  const content = typeof raw.content === 'string' ? raw.content : ''
  const createdAt = typeof raw.createdAt === 'number' ? raw.createdAt : Date.now()

  // 步骤 1:识别状态。刷新页面时残留的 streaming 一律视为「中断」。
  let status: MessageStatus = 'done'
  let error: string | null = null
  if (raw.status === 'error') {
    status = 'error'
    error = typeof raw.error === 'string' ? raw.error : null
  } else if (raw.status === 'stopped') {
    status = 'stopped'
  } else if (raw.status === 'streaming') {
    // 空占位没有保留价值;有内容则标记为可重试的错误
    if (!content) return null
    status = 'error'
    error = '上次生成在页面刷新时中断'
  }

  return { id: raw.id, role: raw.role, content, createdAt, status, error }
}

function normalizeConversation(raw: unknown): Conversation | null {
  if (!isRecord(raw)) return null
  if (typeof raw.id !== 'string' || !raw.id) return null

  const messages = Array.isArray(raw.messages)
    ? raw.messages
        .map(normalizeMessage)
        .filter((m): m is ChatMessage => m !== null)
        .slice(-MAX_MESSAGES_PER_CONVERSATION)
    : []

  const createdAt = typeof raw.createdAt === 'number' ? raw.createdAt : Date.now()
  const updatedAt = typeof raw.updatedAt === 'number' ? raw.updatedAt : createdAt
  const rawTitle = typeof raw.title === 'string' ? raw.title.trim() : ''

  // 标题缺失时用第一条用户消息兜底,再不行才用默认值
  const firstUser = messages.find((m) => m.role === 'user')

  /**
   * 刷新恢复:最后一条是用户消息,说明上一轮回答没来得及落盘。
   * 有两种来源 —— 发送时只存了空占位(被 normalizeMessage 丢弃),
   * 或者用户停止时一个字都没输出(占位被删)。
   * 补一条可重试的错误消息,用户不必重新输入问题。
   */
  const lastMessage = messages[messages.length - 1]
  if (lastMessage && lastMessage.role === 'user') {
    messages.push({
      id: newId(),
      role: 'assistant',
      content: '',
      createdAt: lastMessage.createdAt,
      status: 'error',
      error: '上次生成在页面刷新时中断,可点击重试',
    })
  }

  return {
    id: raw.id,
    title: rawTitle || (firstUser ? makeTitle(firstUser.content) : DEFAULT_TITLE),
    createdAt,
    updatedAt,
    messages,
  }
}

/** 从 localStorage 恢复历史;任何异常都退化为「没有历史」,不能阻塞应用启动 */
function loadConversations(): Conversation[] {
  let raw: string | null = null
  try {
    raw = localStorage.getItem(AGENT_CHAT_KEY)
  } catch {
    return []
  }
  if (!raw) return []

  try {
    const parsed: unknown = JSON.parse(raw)
    if (!isRecord(parsed) || !Array.isArray(parsed.conversations)) {
      localStorage.removeItem(AGENT_CHAT_KEY)
      return []
    }

    return parsed.conversations
      .map(normalizeConversation)
      .filter((c): c is Conversation => c !== null)
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_CONVERSATIONS)
  } catch {
    // 被手改坏 / 旧版本格式不兼容,整份丢弃比带着坏数据运行更安全
    try {
      localStorage.removeItem(AGENT_CHAT_KEY)
    } catch {
      // localStorage 本身不可用时忽略
    }
    return []
  }
}

/** 恢复上次选中的会话;找不到就退回最新一个 */
function loadActiveId(conversations: Conversation[]): string | null {
  try {
    const saved = localStorage.getItem(AGENT_ACTIVE_KEY)
    if (saved && conversations.some((c) => c.id === saved)) return saved
  } catch {
    // ignore
  }
  return conversations[0]?.id ?? null
}

/** 构造用于落盘的纯对象副本,并按上限裁剪 */
function buildStorageCopy(
  conversations: Conversation[],
  maxConversations: number,
  maxMessages: number,
): Conversation[] {
  return conversations.slice(0, maxConversations).map((conversation) => ({
    id: conversation.id,
    title: conversation.title,
    createdAt: conversation.createdAt,
    updatedAt: conversation.updatedAt,
    messages: conversation.messages.slice(-maxMessages).map((message) => ({
      id: message.id,
      role: message.role,
      content:
        message.content.length > MAX_STORED_CONTENT_CHARS
          ? `${message.content.slice(0, MAX_STORED_CONTENT_CHARS)}\n\n[内容过长,已截断]`
          : message.content,
      createdAt: message.createdAt,
      status: message.status === 'streaming' ? 'error' : message.status,
      error: message.error ?? null,
    })),
  }))
}

/**
 * 写入历史。配额不足时逐级降级(少留会话、少留消息),
 * 仍然失败就清掉存储键 —— 内存里的对话必须继续可用。
 */
function persistConversations(conversations: Conversation[]): void {
  const attempts: Conversation[][] = [
    buildStorageCopy(conversations, MAX_CONVERSATIONS, MAX_MESSAGES_PER_CONVERSATION),
    buildStorageCopy(conversations, 10, 40),
    buildStorageCopy(conversations, 5, 20),
  ]

  for (const copy of attempts) {
    try {
      localStorage.setItem(
        AGENT_CHAT_KEY,
        JSON.stringify({ version: STORAGE_VERSION, conversations: copy }),
      )
      return
    } catch {
      // 继续尝试更小的副本
    }
  }

  try {
    localStorage.removeItem(AGENT_CHAT_KEY)
  } catch {
    // 连 removeItem 都失败就彻底放弃持久化
  }
}

// ---------------------------------------------------------------------------
// Store
// ---------------------------------------------------------------------------

export const useAgentStore = defineStore('agent', () => {
  // 步骤 1:初始化时同步恢复历史与选中会话
  const conversations = ref<Conversation[]>(loadConversations())
  const activeId = ref<string | null>(loadActiveId(conversations.value))

  // 全局同时只允许一条流:切换会话或离开页面都会中止当前流
  const streaming = ref(false)
  const streamingConversationId = ref<string | null>(null)

  const activeConversation = computed(
    () => conversations.value.find((c) => c.id === activeId.value) ?? null,
  )

  const activeMessages = computed<ChatMessage[]>(
    () => activeConversation.value?.messages ?? EMPTY_MESSAGES,
  )

  // ---- 非响应式的流式内部状态 ----
  let controller: AbortController | null = null
  let rafId: number | null = null
  let pendingDelta = ''
  /** 当前正在追加的目标消息;持有引用即可直接改 content */
  let flushTarget: ChatMessage | null = null
  let lastFlushAt = 0
  /** 流序号:旧的流收尾时不能清掉新流的状态 */
  let streamSeq = 0

  function persistActive(): void {
    try {
      if (activeId.value) localStorage.setItem(AGENT_ACTIVE_KEY, activeId.value)
      else localStorage.removeItem(AGENT_ACTIVE_KEY)
    } catch {
      // ignore
    }
  }

  function saveHistory(): void {
    persistConversations(conversations.value)
    persistActive()
  }

  // ---- 流式增量合并 ----

  /** 把 pendingDelta 真正写进消息内容 */
  function flushPending(): void {
    if (!pendingDelta || !flushTarget) return
    flushTarget.content += pendingDelta
    pendingDelta = ''
  }

  /**
   * 用 rAF 调度一次刷新,并用 40ms 间隔限制频率。
   * 如果离上次刷新太近,就再等一帧,保证最多约 25 次/秒。
   */
  function scheduleFlush(): void {
    if (rafId !== null) return

    const run = () => {
      rafId = null
      const now = performance.now()
      if (now - lastFlushAt < MIN_FLUSH_INTERVAL_MS) {
        rafId = requestAnimationFrame(run)
        return
      }
      lastFlushAt = now
      flushPending()
    }

    rafId = requestAnimationFrame(run)
  }

  /** 流结束时同步收尾:取消未执行的帧,把残余增量写完 */
  function cancelFlush(): void {
    if (rafId !== null) {
      cancelAnimationFrame(rafId)
      rafId = null
    }
    flushPending()
    flushTarget = null
  }

  // ---- 会话增删选 ----

  function createConversation(): Conversation {
    const now = Date.now()
    const conversation: Conversation = {
      id: newId(),
      title: DEFAULT_TITLE,
      createdAt: now,
      updatedAt: now,
      messages: [],
    }

    conversations.value.unshift(conversation)

    // ⚠️ 必须取回数组里的响应式代理再返回。
    // `unshift` 存进去的是原始对象,直接返回它的话,后续 `assistant.content += delta`
    // 走的是原始对象的属性赋值,绕过了 Vue 的 set 拦截:
    // 首个会话的流式文本不会逐字刷新,只在最后 streaming 变化时一次性出现。
    const stored = conversations.value[0] ?? conversation
    activeId.value = stored.id
    saveHistory()
    return stored
  }

  /** 新建对话;当前会话本来就是空的就复用,避免堆积一堆空会话 */
  function newChat(): Conversation {
    // 同一时刻只允许一条流:新建对话也要先中止旧流,已输出内容会保留
    if (streaming.value) stop()

    const current = activeConversation.value
    if (current && current.messages.length === 0) return current
    return createConversation()
  }

  function selectConversation(id: string): void {
    if (activeId.value === id) return
    // 切走时中止当前流,已输出的内容会由 finally 保留下来
    if (streaming.value) stop()
    activeId.value = id
    persistActive()
  }

  function deleteConversation(id: string): void {
    if (streamingConversationId.value === id) stop()

    const index = conversations.value.findIndex((c) => c.id === id)
    if (index < 0) return

    conversations.value.splice(index, 1)
    if (activeId.value === id) {
      activeId.value = conversations.value[0]?.id ?? null
    }
    saveHistory()
  }

  // ---- 流式对话 ----

  /**
   * 执行一轮流式问答。
   *
   * @param appendUser 是否先把用户问题写进历史。重新生成时为 false,
   *                   因为原问题已经在列表里了。
   */
  async function runStream(
    target: Conversation,
    prompt: string,
    appendUser: boolean,
  ): Promise<void> {
    if (streaming.value) return

    // 始终拿数组里的响应式代理再改。直接改调用方递进来的原始对象会绕过
    // Vue 的 set 拦截,流式文本和状态都不会触发视图更新。
    const conversation = conversations.value.find((c) => c.id === target.id) ?? target

    const seq = ++streamSeq
    const now = Date.now()

    // 步骤 1:写入用户消息。首条用户消息顺便生成会话标题。
    if (appendUser) {
      const isFirstUserMessage = conversation.messages.every((m) => m.role !== 'user')
      conversation.messages.push({
        id: newId(),
        role: 'user',
        content: prompt,
        createdAt: now,
        status: 'done',
      })
      if (isFirstUserMessage) conversation.title = makeTitle(prompt)
    }

    // 步骤 2:先放一个空的 assistant 占位,流式内容直接往里追加
    const assistant: ChatMessage = {
      id: newId(),
      role: 'assistant',
      content: '',
      createdAt: now,
      status: 'streaming',
      error: null,
    }
    conversation.messages.push(assistant)
    conversation.updatedAt = now

    // push 进去的是原始对象,读回来才是响应式代理;后续增量必须写在代理上
    const liveAssistant =
      conversation.messages[conversation.messages.length - 1] ?? assistant

    // 步骤 3:初始化本次流的缓冲状态
    pendingDelta = ''
    flushTarget = liveAssistant
    lastFlushAt = 0

    streaming.value = true
    streamingConversationId.value = conversation.id
    controller = new AbortController()

    // 用户问题先落盘:即使刷新时回答还没结束,提问也不会丢
    saveHistory()

    try {
      await streamAgentChat({
        sessionId: conversation.id,
        message: prompt,
        signal: controller.signal,
        onDelta: (delta) => {
          pendingDelta += delta
          scheduleFlush()
        },
      })

      if (seq !== streamSeq) return

      // 步骤 4:干净结束。空回答大概率是上游异常,标成可重试的错误。
      cancelFlush()
      if (!liveAssistant.content) {
        liveAssistant.status = 'error'
        liveAssistant.error = '未收到回答,请重试'
      } else {
        liveAssistant.status = 'done'
      }
    } catch (error) {
      if (seq !== streamSeq) return

      cancelFlush()

      // 步骤 5:区分「用户停止」和「真失败」。两种情况都保留已输出内容。
      if (isAbortError(error)) {
        if (liveAssistant.content) {
          liveAssistant.status = 'stopped'
        } else {
          // 一个字都没输出就被停止,删掉空占位,别留一条空气泡
          const index = conversation.messages.findIndex((m) => m.id === liveAssistant.id)
          if (index >= 0) conversation.messages.splice(index, 1)
        }
      } else {
        liveAssistant.status = 'error'
        liveAssistant.error = error instanceof Error ? error.message : '生成失败,请重试'
      }
    } finally {
      // 步骤 6:只有当前流才能清理全局状态,防止旧流的收尾覆盖新流
      if (seq === streamSeq) {
        if (rafId !== null) {
          cancelAnimationFrame(rafId)
          rafId = null
        }
        pendingDelta = ''
        flushTarget = null

        streaming.value = false
        streamingConversationId.value = null
        controller = null

        conversation.updatedAt = Date.now()
        saveHistory()
      }
    }
  }

  /** 发送新问题;返回 false 表示被本地校验/流式状态挡下 */
  function send(text: string): boolean {
    const content = text.trim()
    if (!content || streaming.value || content.length > MAX_MESSAGE_LENGTH) return false

    const conversation = activeConversation.value ?? createConversation()
    void runStream(conversation, content, true)
    return true
  }

  /** 停止生成:只 abort,状态由 finally 收尾,避免和新流竞态 */
  function stop(): void {
    controller?.abort()
  }

  /**
   * 重新生成最后一条回答。
   * 要求最后一条消息是 assistant;找到它前面的用户问题后删掉回答重跑。
   */
  function regenerateLast(): void {
    if (streaming.value) return

    const conversation = activeConversation.value
    if (!conversation) return

    const { messages } = conversation
    const last = messages[messages.length - 1]
    if (!last || last.role !== 'assistant') return

    // 从尾部往前找最近一条用户消息
    let userIndex = -1
    for (let i = messages.length - 1; i >= 0; i -= 1) {
      const message = messages[i]
      if (!message) continue
      if (message.role === 'user') {
        userIndex = i
        break
      }
    }
    if (userIndex < 0) return

    const prompt = messages[userIndex]?.content ?? ''
    if (!prompt) return

    // 删掉这条用户消息之后的所有内容(正常情况下只有最后一条 assistant)
    messages.splice(userIndex + 1)
    void runStream(conversation, prompt, false)
  }

  /** 错误重试:等同于重新生成,但只对 error/stopped 状态开放 */
  function retryLast(): void {
    if (streaming.value) return

    const messages = activeConversation.value?.messages
    if (!messages) return

    const last = messages[messages.length - 1]
    if (!last || last.role !== 'assistant') return
    if (last.status !== 'error' && last.status !== 'stopped') return

    regenerateLast()
  }

  return {
    // 状态
    conversations,
    activeId,
    streaming,
    streamingConversationId,
    activeConversation,
    activeMessages,
    // 会话操作
    newChat,
    createConversation,
    selectConversation,
    deleteConversation,
    // 对话操作
    send,
    stop,
    regenerateLast,
    retryLast,
  }
})
