<script setup lang="ts">
/**
 * 工作台
 *
 * 落地页要回答一个问题:"我现在该干什么?"
 * 所以结构是:先给 4 个关键数字,再用一张状态分布图说明积压在哪,
 * 最后列出最近需要我处理的工单。
 *
 * 图表用纯 SVG 手绘,不引第三方图表库 —— 只有一张环形图,引库不划算,
 * 而且手绘能完全贴合主题色变量。
 */
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
// 裸图标名不会被 resolver 自动解析,必须显式导入
import { Plus } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import PriorityTag from '@/components/PriorityTag.vue'
import { WorkOrderStatus, getOrderTypeLabel, getStatusLabel, getStatusTone } from '@/constants/workorder'
import { fetchWorkOrderStats, pageWorkOrders } from '@/api'
import { useUserDirectory } from '@/composables/useDirectories'
import { formatRelative } from '@/utils/datetime'
import { useUserStore } from '@/stores/user'
import type { WorkOrderStats, WorkOrderVO } from '@/types/domain'

const router = useRouter()
const userStore = useUserStore()
const { ensure: ensureUsers, nameOf } = useUserDirectory()

const loading = ref(false)

/** 空对象打底,接口没回来时统计都算 0,模板不用到处判空 */
const stats = ref<WorkOrderStats>({ total: 0, statusCounts: [] })

/** 统计口径由后端按当前用户的数据范围收敛,和工单列表完全一致 */
const distribution = computed(() =>
  stats.value.statusCounts.map((s) => ({
    status: s.status,
    label: getStatusLabel(s.status),
    count: s.count,
  })),
)

function countOf(status: number): number {
  return stats.value.statusCounts.find((s) => s.status === status)?.count ?? 0
}

// ---------------------------------------------------------------------------
// 指标卡
// ---------------------------------------------------------------------------
const metrics = computed(() => [
  {
    key: 'total',
    label: '工单总数',
    value: stats.value.total,
    hint: '当前可见范围内',
    tone: 'brand',
  },
  {
    key: 'active',
    label: '进行中',
    value:
      countOf(WorkOrderStatus.PENDING_REVIEW) +
      countOf(WorkOrderStatus.PENDING_DISPATCH) +
      countOf(WorkOrderStatus.PROCESSING) +
      countOf(WorkOrderStatus.PENDING_ACCEPT),
    hint: '待审核 / 待派单 / 处理中 / 待验收',
    tone: 'plain',
  },
  {
    key: 'timeout',
    label: '已超时',
    value: countOf(WorkOrderStatus.TIMEOUT),
    hint: '需要尽快跟进',
    tone: 'danger',
  },
  {
    key: 'done',
    label: '已完成',
    value: countOf(WorkOrderStatus.COMPLETED),
    hint: '累计闭环',
    tone: 'success',
  },
])

// ---------------------------------------------------------------------------
// 环形图:手工算弧长
// ---------------------------------------------------------------------------
const RADIUS = 54
const STROKE = 16
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

/** 只画有数据的段,并累计出每段的起始偏移 */
const donutSegments = computed(() => {
  const items = distribution.value.filter((d) => d.count > 0)
  const total = items.reduce((s, d) => s + d.count, 0)
  if (total === 0) return []

  let offset = 0
  return items.map((d) => {
    const fraction = d.count / total
    const length = fraction * CIRCUMFERENCE
    const seg = {
      status: d.status,
      label: d.label,
      count: d.count,
      // stroke-dasharray: 这一段画多长、空多长
      dash: `${length} ${CIRCUMFERENCE - length}`,
      // 负偏移让它接在前一段后面;SVG 从 3 点钟方向起算,整体转 -90° 到 12 点
      offset: -offset,
      percent: Math.round(fraction * 100),
      tone: getStatusTone(d.status),
    }
    offset += length
    return seg
  })
})

const doneRate = computed(() => {
  const total = stats.value.total
  if (total === 0) return 0
  return Math.round((countOf(WorkOrderStatus.COMPLETED) / total) * 100)
})

// ---------------------------------------------------------------------------
// 待我处理:按当前用户的权限推断
// ---------------------------------------------------------------------------
const TASK_LIMIT = 6

/** 我这类权限对应哪些状态,按流转顺序排 */
const myStatuses = computed(() => {
  const perms = userStore.perms
  const list: number[] = []
  if (perms.includes('workorder:review')) list.push(WorkOrderStatus.PENDING_REVIEW)
  if (perms.includes('workorder:dispatch')) list.push(WorkOrderStatus.PENDING_DISPATCH)
  if (perms.includes('workorder:process')) list.push(WorkOrderStatus.PROCESSING)
  if (perms.includes('workorder:accept')) list.push(WorkOrderStatus.PENDING_ACCEPT)
  return list
})

const myTasks = ref<WorkOrderVO[]>([])

async function loadTasks() {
  // 管理员是"全部工单"的视角,没有单一待办状态,直接取最新一批
  if (userStore.hasPerm('user:manage')) {
    const res = await pageWorkOrders({ current: 1, size: TASK_LIMIT })
    myTasks.value = res.records
    return
  }

  const statuses = myStatuses.value
  if (statuses.length === 0) {
    myTasks.value = []
    return
  }

  // 一个状态一个请求:接口的 status 只收单值,而用户可能同时是审核人和派单人。
  // 只查最早那个阶段会让派单待办永远看不见,所以这里并发查全部,再在本地合并。
  const pages = await Promise.all(
    statuses.map((status) => pageWorkOrders({ current: 1, size: TASK_LIMIT, status })),
  )
  myTasks.value = pages
    .flatMap((p) => p.records)
    // createTime 是 ISO 且补零到秒,字典序即时间序,不必转 Date
    .sort((a, b) => b.createTime.localeCompare(a.createTime))
    .slice(0, TASK_LIMIT)
}

/** 按当前用户角色给出该做什么的提示 */
const roleHint = computed(() => {
  const perms = userStore.perms
  if (perms.includes('user:manage')) return '你是管理员,可以查看全部工单并管理用户与权限。'
  if (perms.includes('workorder:review')) return '你负责审核,下面是等待你处理的提单。'
  if (perms.includes('workorder:dispatch')) return '你负责派单,下面是等待分配处理人的工单。'
  if (perms.includes('workorder:process')) return '你负责处理,下面是分配给你或待领取的工单。'
  if (perms.includes('workorder:accept')) return '你负责验收,下面是等待你确认结果的工单。'
  return '你当前没有待处理事项。'
})

onMounted(async () => {
  loading.value = true
  void ensureUsers()
  try {
    // 统计比待办重要,先拿到它;待办失败也不该让整页空白
    stats.value = await fetchWorkOrderStats()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '统计数据加载失败')
  } finally {
    loading.value = false
  }
  await loadTasks().catch(() => {
    myTasks.value = []
  })
})

function openOrder(id: number) {
  router.push({ name: 'workorder-detail', params: { id } })
}
</script>

<template>
  <div v-loading="loading" class="dash">
    <PageHeader eyebrow="Dashboard" title="工作台" :description="roleHint">
      <template #actions>
        <el-button @click="router.push({ name: 'workorder-list' })">查看全部工单</el-button>
        <el-button
          v-if="userStore.hasPerm('workorder:create')"
          type="primary"
          @click="router.push({ name: 'workorder-create' })"
        >
          <el-icon><Plus /></el-icon>
          提交工单
        </el-button>
      </template>
    </PageHeader>

    <!-- ==================== 指标卡 ==================== -->
    <section class="metrics">
      <article
        v-for="m in metrics"
        :key="m.key"
        class="metric wo-card"
        :class="`metric--${m.tone}`"
      >
        <p class="wo-eyebrow">{{ m.label }}</p>
        <p class="metric__value wo-num">{{ m.value }}</p>
        <p class="metric__hint">{{ m.hint }}</p>
      </article>
    </section>

    <div class="dash__grid">
      <!-- ==================== 左:状态分布 ==================== -->
      <section class="card wo-card">
        <header class="card__head">
          <h2>状态分布</h2>
          <span class="wo-text-3">共 {{ stats.total }} 条</span>
        </header>

        <div class="dist">
          <!-- 环形图 -->
          <div class="donut">
            <svg :viewBox="`0 0 140 140`" class="donut__svg" role="img" aria-label="工单状态分布">
              <g transform="rotate(-90 70 70)">
                <!-- 底环 -->
                <circle
                  :cx="70"
                  :cy="70"
                  :r="RADIUS"
                  fill="none"
                  stroke="var(--wo-surface-sunken)"
                  :stroke-width="STROKE"
                />
                <!-- 数据段:每段一个圆,用 dasharray 只露出该段 -->
                <circle
                  v-for="seg in donutSegments"
                  :key="seg.status"
                  :cx="70"
                  :cy="70"
                  :r="RADIUS"
                  fill="none"
                  :stroke="`var(--wo-st-${seg.tone}-dot)`"
                  :stroke-width="STROKE"
                  :stroke-dasharray="seg.dash"
                  :stroke-dashoffset="seg.offset"
                  stroke-linecap="butt"
                  class="donut__seg"
                />
              </g>
            </svg>

            <div class="donut__center">
              <b class="wo-num">{{ doneRate }}%</b>
              <span>已完成</span>
            </div>
          </div>

          <!-- 图例:同时充当筛选入口 -->
          <ul class="legend">
            <li
              v-for="seg in donutSegments"
              :key="seg.status"
              :style="{ '--dot': `var(--wo-st-${seg.tone}-dot)` }"
              @click="router.push({ name: 'workorder-list', query: { status: String(seg.status) } })"
            >
              <i class="legend__dot" aria-hidden="true" />
              <span class="legend__label">{{ seg.label }}</span>
              <span class="legend__bar" aria-hidden="true">
                <em :style="{ width: `${seg.percent}%` }" />
              </span>
              <b class="legend__count wo-num">{{ seg.count }}</b>
            </li>
          </ul>
        </div>
      </section>

      <!-- ==================== 右:待我处理 ==================== -->
      <section class="card wo-card">
        <header class="card__head">
          <h2>待我处理</h2>
          <RouterLink :to="{ name: 'workorder-list' }" class="card__more">全部 →</RouterLink>
        </header>

        <ul v-if="myTasks.length" class="tasks">
          <li v-for="o in myTasks" :key="o.id" @click="openOrder(o.id)">
            <div class="tasks__top">
              <span class="tasks__no wo-num">{{ o.orderNo }}</span>
              <StatusTag :status="o.status" size="small" />
            </div>
            <p class="tasks__title">{{ o.title }}</p>
            <div class="tasks__foot">
              <span>{{ getOrderTypeLabel(o.orderType) }}</span>
              <span class="tasks__sep" />
              <PriorityTag :priority="o.priority" />
              <span class="tasks__sep" />
              <span class="wo-text-3">{{ nameOf(o.userId) }}</span>
              <span class="tasks__time wo-text-3">{{ formatRelative(o.createTime) }}</span>
            </div>
          </li>
        </ul>

        <el-empty v-else description="当前没有需要你处理的工单" :image-size="72" />
      </section>
    </div>
  </div>
</template>

<style scoped lang="scss">
.dash {
  display: flex;
  flex-direction: column;
}

// ===========================================================================
// 指标卡
// ===========================================================================
.metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: var(--wo-space-4);
  margin-bottom: var(--wo-space-4);
}

.metric {
  padding: 18px 20px;
  position: relative;
  overflow: hidden;
  transition: box-shadow var(--wo-dur) var(--wo-ease);

  &:hover {
    box-shadow: var(--wo-shadow-sm);
  }

  // 顶部一条 2px 的语义色细线,是卡片之间唯一的区别
  &::before {
    content: '';
    position: absolute;
    inset: 0 0 auto 0;
    height: 2px;
    background: var(--accent, transparent);
  }
}

.metric--brand {
  --accent: var(--wo-brand);
}
.metric--plain {
  --accent: var(--wo-hairline-strong);
}
.metric--danger {
  --accent: var(--wo-st-5-dot);
}
.metric--success {
  --accent: var(--wo-st-4-dot);
}

.metric__value {
  font-size: 32px;
  font-weight: 700;
  line-height: 1.15;
  letter-spacing: -0.03em;
  color: var(--wo-ink-1);
  margin: 6px 0 4px;
}

.metric__hint {
  font-size: 12px;
  color: var(--wo-ink-3);
}

// ===========================================================================
// 两栏
// ===========================================================================
.dash__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--wo-space-4);
  align-items: start;
}

.card {
  padding: 20px 22px;
}

.card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 18px;
  border-bottom: 1px solid var(--wo-hairline);

  h2 {
    font-size: 14px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }

  span {
    font-size: 12.5px;
  }
}

.card__more {
  font-size: 12.5px;
  font-weight: 500;
}

// ===========================================================================
// 环形图 + 图例
// ===========================================================================
.dist {
  display: grid;
  grid-template-columns: 150px minmax(0, 1fr);
  gap: 24px;
  align-items: center;
}

.donut {
  position: relative;
  width: 150px;
  height: 150px;
}

.donut__svg {
  width: 100%;
  height: 100%;
  display: block;
}

// 数据段出现时有个极轻的"生长"动画,一次就够,不要循环
.donut__seg {
  transition: stroke-dasharray var(--wo-dur-slow) var(--wo-ease-out);
}

.donut__center {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;

  b {
    font-size: 24px;
    font-weight: 700;
    letter-spacing: -0.03em;
    color: var(--wo-ink-1);
    line-height: 1.1;
  }

  span {
    font-size: 11px;
    color: var(--wo-ink-3);
    margin-top: 2px;
  }
}

.legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;

  li {
    display: grid;
    grid-template-columns: 8px 56px minmax(0, 1fr) 28px;
    align-items: center;
    gap: 10px;
    padding: 5px 8px;
    border-radius: var(--wo-radius-sm);
    cursor: pointer;
    transition: background-color var(--wo-dur-fast) var(--wo-ease);

    &:hover {
      background: var(--wo-brand-wash);
    }
  }
}

.legend__dot {
  width: 8px;
  height: 8px;
  border-radius: 3px;
  background: var(--dot);
}

.legend__label {
  font-size: 12.5px;
  color: var(--wo-ink-2);
  white-space: nowrap;
}

// 占比条:比纯数字更容易比较
.legend__bar {
  height: 4px;
  border-radius: 999px;
  background: var(--wo-surface-sunken);
  overflow: hidden;

  em {
    display: block;
    height: 100%;
    border-radius: 999px;
    background: var(--dot);
    transition: width var(--wo-dur-slow) var(--wo-ease-out);
  }
}

.legend__count {
  font-size: 12.5px;
  font-weight: 700;
  color: var(--wo-ink-1);
  text-align: right;
}

// ===========================================================================
// 待我处理列表
// ===========================================================================
.tasks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;

  li {
    padding: 12px 10px;
    border-radius: var(--wo-radius-sm);
    cursor: pointer;
    transition: background-color var(--wo-dur-fast) var(--wo-ease);

    &:hover {
      background: var(--wo-brand-wash);
    }

    // 条目之间用发丝线分隔,最后一条不要
    &:not(:last-child) {
      border-bottom: 1px solid var(--wo-hairline);
    }
  }
}

.tasks__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 6px;
}

.tasks__no {
  font-size: 11.5px;
  font-weight: 700;
  letter-spacing: 0.03em;
  color: var(--wo-ink-3);
}

.tasks__title {
  font-size: 13.5px;
  font-weight: 500;
  color: var(--wo-ink-1);
  line-height: 1.5;
  // 两行封顶,避免标题过长把列表撑散
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.tasks__foot {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 7px;
  font-size: 12px;
  color: var(--wo-ink-3);
}

.tasks__sep {
  width: 1px;
  height: 10px;
  background: var(--wo-hairline-strong);
}

.tasks__time {
  margin-left: auto;
  font-variant-numeric: tabular-nums;
}

// ===========================================================================
// 响应式
// ===========================================================================
@media (max-width: 1180px) {
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }

  .dash__grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 620px) {
  .dist {
    grid-template-columns: 1fr;
    justify-items: center;
  }

  .legend {
    width: 100%;
  }
}
</style>
