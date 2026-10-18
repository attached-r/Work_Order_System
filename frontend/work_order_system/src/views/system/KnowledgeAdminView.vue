<script setup lang="ts">
/**
 * 知识库管理
 *
 * 只对接两个管理接口:
 *   - GET  /api/agent/knowledge/status
 *   - POST /api/agent/knowledge/reindex?force=
 *
 * 关键约定(与项目一的 Result 包装不同):
 *   - 成功是裸对象;
 *   - 失败是 HTTP 403/409/500 + {code,message};
 *   - RAG 关闭时控制器不注册,表现为 404。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Refresh, Warning } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  AgentApiError,
  getKnowledgeStatus,
  isAbortError,
  reindexKnowledge,
} from '@/api'
import type { KnowledgeStatus, ReindexResult } from '@/api'
import { useUserStore } from '@/stores/user'
import { formatTime } from '@/utils/datetime'
import { copyText } from '@/utils/clipboard'

const userStore = useUserStore()

const status = ref<KnowledgeStatus | null>(null)
const loading = ref(false)
const loadError = ref<string | null>(null)

/** 403:当前账号/失效 token 没有 user:manage */
const forbidden = ref(false)
/** 404:agent.rag.enabled=false,控制器根本没注册 */
const ragDisabled = ref(false)

const reindexing = ref(false)
const force = ref(false)
const lastResult = ref<ReindexResult | null>(null)

/** 重建可能在后台持续几十秒,ingesting=true 时轮询状态 */
const POLL_INTERVAL_MS = 5_000
let pollTimer: ReturnType<typeof setInterval> | null = null
let disposed = false

// ---------------------------------------------------------------------------
// 加载与轮询
// ---------------------------------------------------------------------------

const alertTitle = computed(() => {
  if (forbidden.value) return '需要管理员权限'
  if (ragDisabled.value) return '知识库未启用'
  return '状态加载失败'
})

/** 是否有重建按钮的可用条件:有权限、已启用、当前没有任务在跑 */
const reindexDisabled = computed(
  () =>
    reindexing.value ||
    status.value?.ingesting === true ||
    status.value?.enabled !== true,
)

function stopPolling(): void {
  if (pollTimer !== null) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** ingesting 为真才轮询;状态变化后由 load() 自己校准 */
function syncPolling(): void {
  const shouldPoll = status.value?.ingesting === true

  if (shouldPoll && pollTimer === null) {
    pollTimer = setInterval(() => {
      void load(false)
    }, POLL_INTERVAL_MS)
  } else if (!shouldPoll) {
    stopPolling()
  }
}

async function load(showLoading = true): Promise<void> {
  if (showLoading) loading.value = true
  loadError.value = null

  try {
    const result = await getKnowledgeStatus()
    if (disposed) return

    status.value = result
    forbidden.value = false
    ragDisabled.value = false
  } catch (error) {
    if (disposed) return

    if (error instanceof AgentApiError) {
      // 步骤 1:403 与「项目一登录失效」不是一回事,这里只提示,不清 token
      if (error.status === 403) {
        forbidden.value = true
        ragDisabled.value = false
        status.value = null
      } else if (error.status === 404) {
        // 步骤 2:RAG 关闭时接口整体不存在
        ragDisabled.value = true
        forbidden.value = false
        status.value = null
      }
      loadError.value = error.message
    } else {
      loadError.value = error instanceof Error ? error.message : '知识库状态加载失败'
    }
  } finally {
    if (!disposed) {
      if (showLoading) loading.value = false
      syncPolling()
    }
  }
}

// ---------------------------------------------------------------------------
// 重建
// ---------------------------------------------------------------------------

async function handleReindex(): Promise<void> {
  if (reindexDisabled.value) return

  try {
    await ElMessageBox.confirm(
      force.value
        ? '将忽略「语料未变化」判断,强制重建整个知识库索引。重建可能需要数十秒,期间对话仍使用旧索引。'
        : '将对知识库语料做一次检查并重建索引。若语料与 embedding 配置未变化,会直接跳过。重建可能需要数十秒。',
      '重建知识库索引',
      {
        confirmButtonText: '开始重建',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return // 用户取消
  }

  reindexing.value = true
  lastResult.value = null

  try {
    const result = await reindexKnowledge(force.value)
    if (disposed) return

    lastResult.value = result

    if (result.status === 'INGESTED') {
      ElMessage.success('知识库重建完成')
    } else {
      ElMessage.info('索引已是最新,无需重建')
    }

    // 重建完成后刷新状态卡,拿到新的版本号 / 计数 / 入库时间
    await load(false)
  } catch (error) {
    if (disposed) return
    handleReindexError(error)
  } finally {
    if (!disposed) reindexing.value = false
  }
}

function handleReindexError(error: unknown): void {
  if (isAbortError(error)) return

  if (error instanceof AgentApiError) {
    // 409:单飞锁 —— 已有重建在跑,刷新状态让界面看到 ingesting
    if (error.status === 409) {
      ElMessage.warning('知识库重建正在进行中,请稍后再试')
      void load(false)
      return
    }

    // 403:权限/登录态问题;保留当前状态,只提示
    if (error.status === 403) {
      ElMessage.error(error.message || '需要管理员权限(user:manage)')
      return
    }

    // 超时:请求断了但后台可能仍在重建,提示稍后刷新
    if (error.code === -1) {
      ElMessage.warning(error.message || '请求超时,请稍后刷新状态')
      void load(false)
      return
    }

    ElMessage.error(error.message || '知识库重建失败')
    return
  }

  ElMessage.error(error instanceof Error ? error.message : '知识库重建失败')
}

// ---------------------------------------------------------------------------
// 展示辅助
// ---------------------------------------------------------------------------

function ingestedText(value: string | null | undefined): string {
  return value ? formatTime(value, true) : '尚未入库'
}

async function copyCorpusHash(): Promise<void> {
  const hash = lastResult.value?.corpusHash
  if (!hash) return

  const ok = await copyText(hash)
  if (ok) ElMessage.success('已复制语料指纹')
  else ElMessage.error('复制失败,请手动选择')
}

// ---------------------------------------------------------------------------
// 生命周期
// ---------------------------------------------------------------------------

onMounted(() => {
  void load()
})

onUnmounted(() => {
  disposed = true
  stopPolling()
})
</script>

<template>
  <div class="kb">
    <PageHeader
      eyebrow="System"
      title="知识库管理"
      description="查看 Agent 检索索引的运行状态,并在语料更新后重建索引。重建期间对话仍使用旧索引。"
    >
      <template #actions>
        <el-button :loading="loading" @click="load()">
          <el-icon><Refresh /></el-icon>
          刷新状态
        </el-button>
      </template>
    </PageHeader>

    <!-- 403 / 404 / 其它加载错误:都用同一条提示带,文案区分场景 -->
    <div v-if="loadError" class="kb-alert" :class="{ 'is-forbidden': forbidden || ragDisabled }">
      <el-icon :size="16"><Warning /></el-icon>
      <div class="kb-alert__text">
        <b>{{ alertTitle }}</b>
        <p>{{ loadError }}</p>
      </div>
      <el-button text size="small" @click="load()">重试</el-button>
    </div>

    <!-- 概览 -->
    <div v-loading="loading && !status" class="kb-grid">
      <article class="kb-tile wo-card">
        <span class="wo-eyebrow">Status</span>
        <b class="kb-tile__value">
          <span
            class="kb-tile__dot"
            :class="status?.enabled ? 'is-on' : 'is-off'"
            aria-hidden="true"
          ></span>
          {{ status?.enabled ? '已启用' : '未启用' }}
        </b>
        <span class="kb-tile__hint">
          {{ status ? `向量库 · ${status.store}` : '等待状态…' }}
        </span>
      </article>

      <article class="kb-tile wo-card">
        <span class="wo-eyebrow">Corpus</span>
        <b class="kb-tile__value wo-num">
          {{ status?.documentCount ?? '—' }}
          <i>篇文档</i>
        </b>
        <span class="kb-tile__hint wo-num">{{ status?.chunkCount ?? '—' }} 个切片</span>
      </article>

      <article class="kb-tile wo-card">
        <span class="wo-eyebrow">Embedding</span>
        <b class="kb-tile__value kb-tile__value--model" :title="status?.embeddingModel">
          {{ status?.embeddingModel ?? '—' }}
        </b>
        <span class="kb-tile__hint">
          {{ status ? `${status.embeddingProvider} · ${status.dimensions} 维` : '—' }}
        </span>
      </article>

      <article class="kb-tile wo-card">
        <span class="wo-eyebrow">Version</span>
        <b
          class="kb-tile__value kb-tile__value--version wo-num"
          :title="status?.activeVersion ?? '尚未入库'"
        >
          {{ status?.activeVersion ?? '尚未入库' }}
        </b>
        <span class="kb-tile__hint">{{ ingestedText(status?.ingestedAt) }}</span>
      </article>
    </div>

    <!-- 重建操作 -->
    <section class="kb-card wo-card">
      <header class="kb-card__head">
        <div class="kb-card__intro">
          <p class="wo-eyebrow">Reindex</p>
          <h2>重建索引</h2>
          <p class="kb-card__desc">
            读取 classpath 下的知识语料,重新切分、计算 embedding 并写入向量库。
            重建期间检索继续使用旧索引,不会中断对话。
          </p>
        </div>

        <el-button
          type="primary"
          :loading="reindexing"
          :disabled="reindexDisabled"
          @click="handleReindex"
        >
          {{ reindexing ? '重建中…' : '开始重建' }}
        </el-button>
      </header>

      <label class="kb-force" :class="{ 'is-disabled': reindexing || status?.ingesting }">
        <el-switch
          v-model="force"
          :disabled="reindexing || status?.ingesting === true"
        />
        <span class="kb-force__text">
          <b>强制重建</b>
          <i>忽略「语料与 embedding 配置未变化」的判断,强制重新生成索引。</i>
        </span>
      </label>

      <p v-if="status?.ingesting" class="kb-ingesting">
        <span class="kb-ingesting__dot" aria-hidden="true"></span>
        已有重建任务正在执行,状态会自动刷新…
      </p>
    </section>

    <!-- 最近一次重建结果 -->
    <section v-if="lastResult" class="kb-result wo-card">
      <header class="kb-result__head">
        <el-tag
          :type="lastResult.status === 'INGESTED' ? 'success' : 'info'"
          effect="light"
          round
        >
          {{ lastResult.status === 'INGESTED' ? '已重建' : '已跳过' }}
        </el-tag>
        <b>{{ lastResult.message }}</b>
      </header>

      <dl class="kb-result__grid">
        <div>
          <dt>活跃版本</dt>
          <dd class="wo-num">{{ lastResult.activeVersion }}</dd>
        </div>
        <div>
          <dt>文档 / 切片</dt>
          <dd class="wo-num">
            {{ lastResult.documentCount }} / {{ lastResult.chunkCount }}
          </dd>
        </div>
        <div class="kb-result__hash">
          <dt>语料指纹</dt>
          <dd>
            <code>{{ lastResult.corpusHash }}</code>
            <button type="button" title="复制语料指纹" @click="copyCorpusHash">
              <el-icon :size="13"><CopyDocument /></el-icon>
            </button>
          </dd>
        </div>
      </dl>
    </section>

    <!-- 权限兜底:正常路由不会让非管理员进来,这里只解释 403 -->
    <p v-if="forbidden" class="kb-footnote">
      当前账号 {{ userStore.displayName }} 没有 user:manage 权限,无法查看或重建知识库。
    </p>
  </div>
</template>

<style scoped lang="scss">
.kb {
  display: flex;
  flex-direction: column;
}

// ---- 错误提示 ----
.kb-alert {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: var(--wo-space-4);
  padding: 12px 14px;
  border: 1px solid var(--wo-st-5-bg);
  border-radius: var(--wo-radius);
  background: var(--wo-st-5-bg);
  color: var(--wo-st-5-fg);
  font-size: 12.5px;

  &.is-forbidden {
    border-color: var(--wo-st-0-bg);
    background: var(--wo-st-0-bg);
    color: var(--wo-st-0-fg);
  }

  .kb-alert__text {
    flex: 1;
    min-width: 0;

    b {
      display: block;
      font-size: 13px;
      font-weight: 600;
    }

    p {
      margin-top: 2px;
      line-height: 1.6;
      opacity: 0.9;
      overflow-wrap: anywhere;
    }
  }
}

// ---- 四张状态卡 ----
.kb-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: var(--wo-space-3);
  min-height: 96px;
  margin-bottom: var(--wo-space-4);
}

.kb-tile {
  display: flex;
  flex-direction: column;
  gap: 4px;
  min-width: 0;
  padding: 14px 16px 13px;
}

.kb-tile__value {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
  font-size: 18px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--wo-ink-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;

  i {
    font-style: normal;
    font-size: 11px;
    font-weight: 500;
    color: var(--wo-ink-4);
  }
}

.kb-tile__value--model,
.kb-tile__value--version {
  font-size: 14px;
  font-weight: 600;
}

.kb-tile__dot {
  width: 7px;
  height: 7px;
  flex: none;
  border-radius: 50%;
  background: var(--wo-ink-4);

  &.is-on {
    background: var(--wo-st-4-dot);
    box-shadow: 0 0 0 3px var(--wo-st-4-bg);
  }

  &.is-off {
    background: var(--wo-st-6-dot);
  }
}

.kb-tile__hint {
  font-size: 11.5px;
  color: var(--wo-ink-4);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

// ---- 重建卡 ----
.kb-card {
  padding: 18px 18px 16px;
  margin-bottom: var(--wo-space-4);
}

.kb-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--wo-space-4);

  h2 {
    margin-top: 4px;
    font-size: 16px;
    font-weight: 700;
  }
}

.kb-card__intro {
  min-width: 0;
}

.kb-card__desc {
  margin-top: 5px;
  max-width: 68ch;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--wo-ink-3);
}

.kb-force {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-top: 16px;
  padding: 10px 12px;
  border: 1px solid var(--wo-hairline);
  border-radius: var(--wo-radius-sm);
  background: var(--wo-surface-raised);
  cursor: pointer;

  &.is-disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }
}

.kb-force__text {
  display: flex;
  flex-direction: column;
  line-height: 1.5;

  b {
    font-size: 13px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }

  i {
    font-style: normal;
    font-size: 11.5px;
    color: var(--wo-ink-3);
  }
}

.kb-ingesting {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 12px;
  font-size: 12px;
  color: var(--wo-st-2-fg);
}

.kb-ingesting__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--wo-st-2-dot);
  animation: kb-pulse 1.2s infinite ease-in-out;
}

@keyframes kb-pulse {
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

// ---- 结果卡 ----
.kb-result {
  padding: 16px 18px;
}

.kb-result__head {
  display: flex;
  align-items: center;
  gap: 10px;

  b {
    font-size: 13.5px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }
}

.kb-result__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: var(--wo-space-3);
  margin: 14px 0 0;

  > div {
    min-width: 0;
  }

  dt {
    font-size: 11px;
    color: var(--wo-ink-4);
  }

  dd {
    margin: 3px 0 0;
    font-size: 12.5px;
    color: var(--wo-ink-2);
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }
}

.kb-result__hash {
  grid-column: 1 / -1;

  dd {
    display: flex;
    align-items: center;
    gap: 6px;
  }

  code {
    min-width: 0;
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    padding: 2px 6px;
    border: 1px solid var(--wo-hairline);
    border-radius: 4px;
    background: var(--wo-surface-sunken);
    font-family: 'SFMono-Regular', Consolas, monospace;
    font-size: 11.5px;
  }

  button {
    flex: none;
    display: grid;
    place-items: center;
    width: 24px;
    height: 24px;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--wo-ink-3);
    cursor: pointer;

    &:hover {
      color: var(--wo-brand);
      background: var(--wo-brand-wash);
    }
  }
}

.kb-footnote {
  font-size: 11.5px;
  color: var(--wo-ink-4);
}

// ---- 响应式 ----
@media (max-width: 1180px) {
  .kb-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 620px) {
  .kb-grid,
  .kb-result__grid {
    grid-template-columns: 1fr;
  }

  .kb-card__head {
    flex-direction: column;
  }
}
</style>
