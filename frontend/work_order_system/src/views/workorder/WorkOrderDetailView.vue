<script setup lang="ts">
/**
 * 工单详情
 *
 * 布局:左侧主内容(描述 / 资源 / 流转记录)+ 右侧常驻操作栏。
 * 操作栏 sticky 跟随,工单再长也不用滚回去找按钮。
 *
 * 可用动作由状态机 + 当前用户权限共同决定(constants/workorder.ts 的 availableActions),
 * 前端只负责决定"按钮显不显示",真正的合法性仍由后端校验。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
// 裸图标名不会被 resolver 自动解析,必须显式导入
import { ArrowLeft, InfoFilled, Right } from '@element-plus/icons-vue'
import StatusTag from '@/components/StatusTag.vue'
import PriorityTag from '@/components/PriorityTag.vue'
import {
  availableActions,
  getOperateLabel,
  getOrderTypeLabel,
  getStatusLabel,
  getStatusTone,
  isTerminal,
} from '@/constants/workorder'
import type { WorkOrderAction } from '@/constants/workorder'
import {
  acceptWorkOrder,
  dispatchWorkOrder,
  getWorkOrder,
  processWorkOrder,
  reviewWorkOrder,
  withdrawWorkOrder,
} from '@/api'
import { useDepartmentDirectory, useUserDirectory } from '@/composables/useDirectories'
import { formatRemaining, formatTime } from '@/utils/datetime'
import { useUserStore } from '@/stores/user'
import type { WorkOrderDetailVO } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
// 工单只带 userId / handlerId / departmentId,姓名与部门名靠这两份目录补齐
const { ensure: ensureUsers, nameOf: userNameOf, allUsers } = useUserDirectory()
const { ensure: ensureDepts, nameOf: deptNameOf } = useDepartmentDirectory()

const loading = ref(false)
const detail = ref<WorkOrderDetailVO | null>(null)

const orderId = computed(() => {
  const raw = route.params.id
  // noUncheckedIndexedAccess 下 params.id 可能是数组,统一收敛成字符串
  const v = Array.isArray(raw) ? raw[0] : raw
  const n = Number(v)
  return Number.isFinite(n) ? n : 0
})

/** 未分配比 "—" 更能说明「还没派单」这件事 */
function userName(id: number | null): string {
  return id === null ? '未分配' : userNameOf(id)
}

const deptName = computed(() => deptNameOf(detail.value?.departmentId))

/** 当前用户在该工单上能做什么 */
const actions = computed<WorkOrderAction[]>(() => {
  if (!detail.value) return []
  return availableActions(detail.value.status, userStore.perms)
})

const hasActions = computed(() => actions.value.length > 0)
const terminal = computed(() => isTerminal(detail.value?.status))

const remaining = computed(() => formatRemaining(detail.value?.expireTime ?? null))

/**
 * 处理人候选:启用中的处理人。
 * 依赖用户目录 —— 该接口登录即可读,所以只有 workorder:dispatch 的派单人
 * 也能拿到候选;拿不到时列表为空,弹窗里会提示"候选为空",而不是假装能派单。
 */
const handlerOptions = computed(() =>
  allUsers.value.filter((u) => u.roles.includes('HANDLER') && u.status === 1),
)

async function load() {
  loading.value = true
  try {
    detail.value = await getWorkOrder(orderId.value)
  } catch (e) {
    detail.value = null
    ElMessage.error(e instanceof Error ? e.message : '工单加载失败')
  } finally {
    loading.value = false
  }
}

// ===========================================================================
// 操作弹窗
// ===========================================================================
type ActionKey = WorkOrderAction['key']

const dialogVisible = ref(false)
const dialogAction = ref<ActionKey | null>(null)
const submitting = ref(false)

const form = reactive({
  approved: true,
  remark: '',
  handlerId: null as number | null,
})

const ACTION_TITLE: Record<ActionKey, string> = {
  review: '审核工单',
  dispatch: '派单',
  transfer: '转派',
  process: '处理完成',
  accept: '验收工单',
  withdraw: '撤回 / 取消',
  resubmit: '重新提交',
}

/** 只有这几个动作需要填说明才建议必填,其余可空 */
const REMARK_REQUIRED: ActionKey[] = ['review', 'accept', 'withdraw']
const REMARK_HINT: Record<string, string> = {
  review: '驳回时请写明原因,便于提单人补充信息',
  dispatch: '可填写处理要求、期望完成时间等',
  transfer: '请说明转派原因',
  process: '简述处理过程与结果',
  accept: '退回时请写明问题所在',
  withdraw: '请说明撤回或取消的原因',
}

function openDialog(key: ActionKey) {
  dialogAction.value = key
  form.approved = true
  form.remark = ''
  form.handlerId = detail.value?.handlerId ?? null
  dialogVisible.value = true
}

const remarkRequired = computed(
  () => dialogAction.value !== null && REMARK_REQUIRED.includes(dialogAction.value),
)

async function submitDialog() {
  const key = dialogAction.value
  if (!key || !detail.value) return

  if ((key === 'dispatch' || key === 'transfer') && form.handlerId === null) {
    ElMessage.warning('请选择处理人')
    return
  }
  if (remarkRequired.value && !form.remark.trim()) {
    ElMessage.warning('请填写说明')
    return
  }

  submitting.value = true
  const id = detail.value.id
  // 空串统一转成 null,免得后端把 "" 当有效备注存下来
  const remark = form.remark.trim() || null
  try {
    // 派单与转派共用一个接口:后端按工单当前状态区分要 workorder:dispatch
    // 还是 workorder:transfer,前端不必分开
    switch (key) {
      case 'review':
        await reviewWorkOrder(id, { approved: form.approved, remark })
        break
      case 'dispatch':
      case 'transfer':
        await dispatchWorkOrder(id, { handlerId: form.handlerId as number, remark })
        break
      case 'process':
        await processWorkOrder(id, { remark })
        break
      case 'accept':
        await acceptWorkOrder(id, { approved: form.approved, remark })
        break
      case 'withdraw':
        await withdrawWorkOrder(id, { remark })
        break
      default:
        // resubmit 走页面跳转,不会进到这个弹窗
        return
    }
    ElMessage.success(`「${ACTION_TITLE[key]}」已提交`)
    dialogVisible.value = false
    await load()
  } catch (e) {
    // 状态机不允许、无权限、已被他人抢先处理等,都靠后端这句话说清楚
    ElMessage.error(e instanceof Error ? e.message : '操作失败,请稍后重试')
  } finally {
    submitting.value = false
  }
}

/** 重新提交:跳到提单页并带上 id,由那边回填原内容 */
function goResubmit() {
  router.push({ name: 'workorder-create', query: { from: String(orderId.value) } })
}

function onAction(key: ActionKey) {
  // 重新提交要改内容,走页面跳转;其余动作都是确认型,弹窗解决
  if (key === 'resubmit') {
    goResubmit()
    return
  }
  openDialog(key)
}

async function confirmTerminalHint() {
  await ElMessageBox.alert('该工单已进入终态,不能再执行任何操作。', '提示', {
    confirmButtonText: '知道了',
  })
}

onMounted(() => {
  void load()
  // 目录只用于补名字,失败了也不影响工单本身,所以不 await、不阻塞
  void ensureUsers()
  void ensureDepts()
})
</script>

<template>
  <div v-loading="loading" class="detail">
    <!-- 顶部返回 + 面包屑式的上下文 -->
    <div class="detail__top">
      <el-button text class="back" @click="router.push({ name: 'workorder-list' })">
        <el-icon><ArrowLeft /></el-icon>
        返回列表
      </el-button>
    </div>

    <template v-if="detail">
      <div class="detail__grid">
        <!-- ==================== 左:主内容 ==================== -->
        <div class="detail__main">
          <!-- 标题卡 -->
          <section class="hero wo-card">
            <div class="hero__meta">
              <span class="hero__no wo-num">{{ detail.orderNo }}</span>
              <StatusTag :status="detail.status" />
              <span class="hero__sep" />
              <span class="hero__type">{{ getOrderTypeLabel(detail.orderType) }}</span>
              <span class="hero__sep" />
              <PriorityTag :priority="detail.priority" /> <span class="wo-text-3">优先级</span>
            </div>

            <h1 class="hero__title">{{ detail.title }}</h1>

            <div class="hero__times">
              <span>创建于 {{ formatTime(detail.createTime, true) }}</span>
              <span class="hero__sep" />
              <span>更新于 {{ formatTime(detail.updateTime, true) }}</span>
              <span class="hero__sep" />
              <span :class="`tone-${remaining.tone}`">{{ remaining.text }}</span>
            </div>
          </section>

          <!-- 驳回 / 取消等结论性备注:放在最显眼处 -->
          <section
            v-if="detail.remark"
            class="notice"
            :style="{
              '--fg': `var(--wo-st-${getStatusTone(detail.status)}-fg)`,
              '--bg': `var(--wo-st-${getStatusTone(detail.status)}-bg)`,
              '--dot': `var(--wo-st-${getStatusTone(detail.status)}-dot)`,
            }"
          >
            <el-icon class="notice__icon"><InfoFilled /></el-icon>
            <div>
              <b>{{ getStatusLabel(detail.status) }}说明</b>
              <p>{{ detail.remark }}</p>
            </div>
          </section>

          <!-- 描述 -->
          <section class="block wo-card">
            <h2 class="block__title">工单描述</h2>
            <p v-if="detail.content" class="content">{{ detail.content }}</p>
            <p v-else class="wo-text-3">提单人未填写描述</p>
          </section>

          <!-- 资源明细 -->
          <section class="block wo-card">
            <h2 class="block__title">
              资源明细
              <span v-if="detail.resources.length" class="wo-num block__badge">
                {{ detail.resources.length }}
              </span>
            </h2>

            <el-table v-if="detail.resources.length" :data="detail.resources" class="res-table">
              <el-table-column prop="resourceName" label="资源名称" min-width="180" />
              <el-table-column prop="resourceType" label="类型" width="110">
                <template #default="{ row }">
                  <span class="wo-text-2">{{ row.resourceType ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="quantity" label="数量" width="80">
                <template #default="{ row }">
                  <span class="wo-num">{{ row.quantity ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="unit" label="单位" width="80">
                <template #default="{ row }">
                  <span class="wo-text-2">{{ row.unit ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="remark" label="备注" min-width="140">
                <template #default="{ row }">
                  <span class="wo-text-3">{{ row.remark ?? '—' }}</span>
                </template>
              </el-table-column>
            </el-table>

            <p v-else class="wo-text-3 empty-hint">该工单未关联资源明细</p>
          </section>

          <!-- 流转记录 -->
          <section class="block wo-card">
            <h2 class="block__title">流转记录</h2>

            <el-timeline v-if="detail.logs.length" class="timeline">
              <el-timeline-item
                v-for="log in detail.logs"
                :key="log.id"
                :timestamp="formatTime(log.createTime, true)"
                placement="top"
                :color="`var(--wo-st-${getStatusTone(log.toStatus)}-dot)`"
              >
                <div class="log">
                  <div class="log__head">
                    <b>{{ getOperateLabel(log.operateType) }}</b>
                    <span class="log__who">{{ userName(log.operatorId) }}</span>
                    <span v-if="log.fromStatus !== null" class="log__flow">
                      {{ getStatusLabel(log.fromStatus) }}
                      <el-icon :size="11"><Right /></el-icon>
                      {{ getStatusLabel(log.toStatus) }}
                    </span>
                  </div>
                  <p v-if="log.remark" class="log__remark">{{ log.remark }}</p>
                </div>
              </el-timeline-item>
            </el-timeline>

            <p v-else class="wo-text-3 empty-hint">暂无流转记录</p>
          </section>
        </div>

        <!-- ==================== 右:操作栏 ==================== -->
        <aside class="detail__side">
          <section class="side-card wo-card">
            <h2 class="side-card__title">操作</h2>

            <div v-if="hasActions" class="acts">
              <el-button
                v-for="a in actions"
                :key="a.key"
                :type="a.type"
                class="acts__btn"
                @click="onAction(a.key)"
              >
                {{ a.label }}
              </el-button>
            </div>

            <div v-else class="acts acts--none">
              <p class="wo-text-3">
                {{ terminal ? '工单已结束,无可用操作' : '当前状态下你没有可执行的操作' }}
              </p>
              <el-button v-if="terminal" text size="small" @click="confirmTerminalHint">
                为什么?
              </el-button>
            </div>
          </section>

          <section class="side-card wo-card">
            <h2 class="side-card__title">基本信息</h2>

            <dl class="facts">
              <div class="facts__row">
                <dt>提单人</dt>
                <dd>{{ userName(detail.userId) }}</dd>
              </div>
              <div class="facts__row">
                <dt>归属部门</dt>
                <dd>{{ deptName }}</dd>
              </div>
              <div class="facts__row">
                <dt>处理人</dt>
                <dd :class="{ 'wo-text-3': detail.handlerId === null }">
                  {{ userName(detail.handlerId) }}
                </dd>
              </div>
              <div class="facts__row">
                <dt>当前状态</dt>
                <dd><StatusTag :status="detail.status" size="small" /></dd>
              </div>
              <div class="facts__row">
                <dt>超时时间</dt>
                <dd class="wo-num" :class="`tone-${remaining.tone}`">
                  {{ detail.expireTime ? formatTime(detail.expireTime) : '不限' }}
                </dd>
              </div>
            </dl>
          </section>
        </aside>
      </div>
    </template>

    <!-- ==================== 操作弹窗 ==================== -->
    <el-dialog
      v-model="dialogVisible"
      :title="dialogAction ? ACTION_TITLE[dialogAction] : ''"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form v-if="dialogAction" label-position="top">
        <!-- 审核 / 验收:通过还是驳回 -->
        <el-form-item v-if="dialogAction === 'review' || dialogAction === 'accept'" label="处理结论">
          <el-radio-group v-model="form.approved">
            <el-radio-button :value="true">
              {{ dialogAction === 'review' ? '审核通过' : '验收通过' }}
            </el-radio-button>
            <el-radio-button :value="false">
              {{ dialogAction === 'review' ? '驳回' : '退回返工' }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <!-- 派单 / 转派:选处理人 -->
        <el-form-item
          v-if="dialogAction === 'dispatch' || dialogAction === 'transfer'"
          label="处理人"
          required
        >
          <el-select v-model="form.handlerId" placeholder="请选择处理人" style="width: 100%">
            <el-option
              v-for="u in handlerOptions"
              :key="u.userId"
              :label="`${u.realName}(${u.username})`"
              :value="u.userId"
            >
              <span>{{ u.realName }}</span>
              <span class="opt-sub">{{ u.departmentName ?? '全局' }}</span>
            </el-option>
            <template #empty>
              <!-- 目录已登录即可读,所以空列表只可能是"确实没有启用中的处理人" -->
              <p class="opt-empty wo-text-3">没有可选的处理人</p>
            </template>
          </el-select>
        </el-form-item>

        <el-form-item :label="dialogAction === 'process' ? '处理说明' : '说明'" :required="remarkRequired">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
            :placeholder="REMARK_HINT[dialogAction] ?? '可填写说明'"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitDialog">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.detail__top {
  margin-bottom: var(--wo-space-3);
}

.back {
  padding-left: 0;
  color: var(--wo-ink-3);

  &:hover {
    color: var(--wo-brand);
  }
}

// 左侧自适应 + 右侧固定 300px
.detail__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--wo-space-4);
  align-items: start;
}

.detail__main {
  display: flex;
  flex-direction: column;
  gap: var(--wo-space-4);
  min-width: 0;
}

// ===========================================================================
// 标题卡
// ===========================================================================
.hero {
  padding: 22px 24px;
}

.hero__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  font-size: 13px;
  color: var(--wo-ink-3);
}

.hero__no {
  font-size: 12.5px;
  font-weight: 700;
  letter-spacing: 0.04em;
  color: var(--wo-ink-3);
}

.hero__sep {
  width: 1px;
  height: 11px;
  background: var(--wo-hairline-strong);
}

.hero__type {
  font-weight: 500;
  color: var(--wo-ink-2);
}

.hero__title {
  margin: 14px 0 12px;
  font-size: 21px;
  font-weight: 700;
  line-height: 1.45;
  letter-spacing: -0.02em;
  color: var(--wo-ink-1);
}

.hero__times {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: var(--wo-ink-3);
  font-variant-numeric: tabular-nums;
}

// 到期时间按紧急程度变色
.tone-normal {
  color: var(--wo-ink-3);
}
.tone-urgent {
  color: var(--wo-st-0-fg);
  font-weight: 600;
}
.tone-overdue {
  color: var(--wo-st-5-fg);
  font-weight: 600;
}

// ===========================================================================
// 结论性备注横幅
// ===========================================================================
.notice {
  display: flex;
  gap: 12px;
  padding: 16px 18px;
  border-radius: var(--wo-radius);
  background: var(--bg);
  border: 1px solid color-mix(in srgb, var(--dot) 26%, transparent);

  b {
    display: block;
    font-size: 13px;
    color: var(--fg);
    margin-bottom: 4px;
  }

  p {
    font-size: 13px;
    line-height: 1.7;
    color: var(--fg);
    opacity: 0.92;
  }
}

.notice__icon {
  color: var(--dot);
  font-size: 17px;
  flex: none;
  margin-top: 1px;
}

// ===========================================================================
// 内容块
// ===========================================================================
.block {
  padding: 20px 24px;
}

.block__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--wo-ink-1);
  padding-bottom: 12px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--wo-hairline);
}

.block__badge {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  display: inline-grid;
  place-items: center;
  border-radius: 999px;
  background: var(--wo-brand-wash);
  color: var(--wo-brand-hover);
  font-size: 11px;
  font-weight: 700;
}

.content {
  font-size: 14px;
  line-height: 1.85;
  color: var(--wo-ink-2);
  // 提单人在 textarea 里敲的换行要原样保留
  white-space: pre-wrap;
}

.empty-hint {
  font-size: 13px;
}

.res-table {
  width: 100%;
}

// ===========================================================================
// 流转记录
// ===========================================================================
.timeline {
  padding-left: 2px;
  // el-timeline 默认底边距偏大,内容多时很占地方
  :deep(.el-timeline-item) {
    padding-bottom: 20px;
  }
}

.log__head {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 13px;

  b {
    color: var(--wo-ink-1);
    font-weight: 600;
  }
}

.log__who {
  color: var(--wo-ink-3);
}

.log__flow {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--wo-surface-sunken);
  color: var(--wo-ink-3);
  font-size: 11px;
  font-weight: 500;
}

.log__remark {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--wo-ink-2);
  padding: 8px 12px;
  background: var(--wo-surface-raised);
  border-left: 2px solid var(--wo-hairline-strong);
  border-radius: 0 var(--wo-radius-sm) var(--wo-radius-sm) 0;
}

// ===========================================================================
// 右侧栏
// ===========================================================================
.detail__side {
  display: flex;
  flex-direction: column;
  gap: var(--wo-space-4);
  // 跟随滚动,工单再长也能直接点到操作
  position: sticky;
  top: calc(var(--wo-header-h) + var(--wo-space-5));
}

.side-card {
  padding: 18px;
}

.side-card__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wo-ink-1);
  padding-bottom: 10px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--wo-hairline);
}

.acts {
  display: flex;
  flex-direction: column;
  gap: 8px;

  :deep(.el-button) {
    width: 100%;
    margin-left: 0;
    height: 38px;
    font-weight: 600;
  }
}

.acts--none {
  text-align: center;
  gap: 4px;

  p {
    font-size: 13px;
    line-height: 1.6;
  }
}

// ---- 属性表 ----
.facts {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.facts__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;

  dt {
    color: var(--wo-ink-3);
    flex: none;
  }

  dd {
    margin: 0;
    color: var(--wo-ink-1);
    font-weight: 500;
    text-align: right;
    min-width: 0;
  }
}

.opt-sub {
  float: right;
  color: var(--wo-ink-3);
  font-size: 12px;
}

.opt-empty {
  padding: 12px 0;
  text-align: center;
  font-size: 12.5px;
}

// ===========================================================================
// 响应式:窄屏把右栏落到底部
// ===========================================================================
@media (max-width: 1080px) {
  .detail__grid {
    grid-template-columns: 1fr;
  }

  .detail__side {
    position: static;
  }
}
</style>
