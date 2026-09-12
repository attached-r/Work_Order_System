<script setup lang="ts">
/**
 * 工单列表
 *
 * 页面的重心是"快速定位到要看的那批工单",所以做了两层筛选:
 *   1. 顶部状态条 —— 带数量的分段控件,一眼看到各状态积压情况,点一下就筛;
 *   2. 下方条件栏 —— 关键词 / 类型 / 状态,处理更精确的组合查询。
 *
 * 两张入口共用同一份 query,切换时状态条与下拉框自动保持一致。
 *
 * 状态条的数字来自独立的 /workorder/stats,而不是当前这一页的行 —— 后者是
 * 分页后的局部,拿它统计会随翻页乱跳。stats 与 page 在后端共用同一段数据范围
 * 拼装逻辑,并同样接收 orderType / keyword,所以两者口径一致;
 * 唯一切不掉的差别是 stats 不接受 status,这正是我们要的:切状态时各档数字保持稳定。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import StatusTag from '@/components/StatusTag.vue'
import PriorityTag from '@/components/PriorityTag.vue'
import {
  ORDER_TYPE_OPTIONS,
  STATUS_OPTIONS,
  getOrderTypeLabel,
  getStatusLabel,
  getStatusTone,
} from '@/constants/workorder'
import { fetchWorkOrderStats, pageWorkOrders } from '@/api'
import { useUserDirectory } from '@/composables/useDirectories'
import { formatRelative } from '@/utils/datetime'
import { useUserStore } from '@/stores/user'
import type { WorkOrderStats, WorkOrderVO } from '@/types/domain'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
// 工单只带 userId / handlerId,姓名靠这份目录补齐(登录即可读;请求失败才回退成「用户 #id」)
const { ensure: ensureUsers, nameOf } = useUserDirectory()

const loading = ref(false)
const rows = ref<WorkOrderVO[]>([])
const total = ref(0)

const query = reactive({
  current: 1,
  size: 10,
  status: null as number | null,
  orderType: null as number | null,
  keyword: '',
})

/** 空对象打底,免得接口没回来时 computed 里到处判空 */
const stats = ref<WorkOrderStats>({ total: 0, statusCounts: [] })

/**
 * 状态条数据:各状态的工单数。
 * 标签取本地常量表而不是后端 statusDesc,和页面其它地方(下拉框、筛选说明)
 * 保持同一份文案;数量才用后端返回的。
 */
const statusCounts = computed(() =>
  STATUS_OPTIONS.map((opt) => ({
    value: opt.value,
    label: opt.label,
    tone: getStatusTone(opt.value),
    count: stats.value.statusCounts.find((s) => s.status === opt.value)?.count ?? 0,
  })),
)

const totalWithoutStatus = computed(() => stats.value.total)

async function load() {
  loading.value = true
  try {
    const res = await pageWorkOrders({
      current: query.current,
      size: query.size,
      status: query.status,
      orderType: query.orderType,
      keyword: query.keyword,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '工单列表加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 刷新状态条。与 load() 分开:切状态只重查列表,状态条不动 ——
 * 既省一次请求,也避免各档数字在点击的瞬间闪一下。
 */
async function loadStats() {
  try {
    stats.value = await fetchWorkOrderStats({
      orderType: query.orderType,
      keyword: query.keyword,
    })
  } catch {
    // 状态条只是辅助,拿不到就退化成 0,不打断列表
    stats.value = { total: 0, statusCounts: [] }
  }
}

/**
 * 关键词 / 类型变了:列表和状态条都要重查。
 *
 * 触发点全部显式调用,不用 watch 兜 —— 否则「重置」这类同时改多个字段的操作
 * 会既触发 watch 又走显式调用,同一个查询发两三遍,响应还可能乱序覆盖。
 */
async function refreshBoth() {
  await Promise.all([load(), loadStats()])
}

function resetFilters() {
  query.keyword = ''
  query.orderType = null
  query.status = null
  query.current = 1
  void refreshBoth()
}

/** 点状态条:再点一次取消筛选。状态只影响列表,状态条数字保持不变 */
function pickStatus(value: number) {
  query.status = query.status === value ? null : value
  query.current = 1
  void load()
}

/** 「全部」:清掉状态筛选 */
function clearStatus() {
  query.status = null
  query.current = 1
  void load()
}

/** 回到第一页并重新查询,用于关键词/下拉框变更 */
function search() {
  query.current = 1
  void refreshBoth()
}

function openDetail(row: WorkOrderVO) {
  router.push({ name: 'workorder-detail', params: { id: row.id } })
}

onMounted(() => {
  // 支持从工作台带 ?status= 跳进来,直接落到对应筛选
  const s = route.query.status
  if (typeof s === 'string' && s !== '') {
    const n = Number(s)
    if (Number.isFinite(n)) query.status = n
  }
  void ensureUsers()
  void refreshBoth()
})
</script>

<template>
  <div class="wo-list">
    <PageHeader
      eyebrow="Work Orders"
      title="工单列表"
      description="查看你有权限访问的全部工单,支持按状态、类型与关键词组合筛选。"
    >
      <template #actions>
        <el-button @click="refreshBoth">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
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

    <!-- ============ 状态条 ============ -->
    <section class="status-bar" aria-label="按状态筛选">
      <button
        type="button"
        class="status-chip"
        :class="{ 'is-active': query.status === null }"
        @click="clearStatus"
      >
        <span class="status-chip__label">全部</span>
        <span class="status-chip__count">{{ totalWithoutStatus }}</span>
      </button>

      <button
        v-for="s in statusCounts"
        :key="s.value"
        type="button"
        class="status-chip"
        :class="{ 'is-active': query.status === s.value, 'is-empty': s.count === 0 }"
        :style="{ '--fg': `var(--wo-st-${s.tone}-fg)`, '--bg': `var(--wo-st-${s.tone}-bg)`, '--dot': `var(--wo-st-${s.tone}-dot)` }"
        @click="pickStatus(s.value)"
      >
        <i class="status-chip__dot" aria-hidden="true" />
        <span class="status-chip__label">{{ s.label }}</span>
        <span class="status-chip__count">{{ s.count }}</span>
      </button>
    </section>

    <!-- ============ 条件栏 ============ -->
    <section class="filters wo-card">
      <el-input
        v-model="query.keyword"
        placeholder="搜索工单标题或编号"
        clearable
        class="filters__search"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select
        v-model="query.orderType"
        placeholder="全部类型"
        clearable
        class="filters__select"
        @change="search"
      >
        <el-option v-for="t in ORDER_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
      </el-select>

      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        class="filters__select"
        @change="search"
      >
        <el-option v-for="s in STATUS_OPTIONS" :key="s.value" :label="s.label" :value="s.value" />
      </el-select>

      <el-button @click="search">查询</el-button>
      <el-button text @click="resetFilters">重置</el-button>
    </section>

    <!-- ============ 表格 ============ -->
    <section class="table-card wo-card">
      <el-table
        v-loading="loading"
        :data="rows"
        row-key="id"
        class="wo-table"
        @row-click="openDetail"
      >
        <el-table-column label="工单编号" width="150">
          <template #default="{ row }">
            <span class="order-no wo-num">{{ row.orderNo }}</span>
          </template>
        </el-table-column>

        <el-table-column label="标题" min-width="230" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="order-title">{{ row.title }}</span>
          </template>
        </el-table-column>

        <el-table-column label="类型" width="104">
          <template #default="{ row }">
            <span class="wo-text-2">{{ getOrderTypeLabel(row.orderType) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="优先级" width="88">
          <template #default="{ row }">
            <PriorityTag :priority="row.priority" />
          </template>
        </el-table-column>

        <el-table-column label="状态" width="106">
          <template #default="{ row }">
            <StatusTag :status="row.status" />
          </template>
        </el-table-column>

        <el-table-column label="提单人" width="112">
          <template #default="{ row }">
            <span class="wo-text-2">{{ nameOf(row.userId) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="处理人" width="112">
          <template #default="{ row }">
            <span :class="row.handlerId === null ? 'wo-text-3' : 'wo-text-2'">
              {{ nameOf(row.handlerId) }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="创建时间" width="118">
          <template #default="{ row }">
            <span class="wo-text-3 wo-num">{{ formatRelative(row.createTime) }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="86" align="right">
          <!-- el-table 的插槽把 row 声明成 DefaultRow(Record<PropertyKey, any>),
               不是 WorkOrderVO,所以在调用点断言回来 -->
          <template #default="{ row }">
            <el-button text type="primary" @click.stop="openDetail(row as WorkOrderVO)">详情</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="没有符合条件的工单">
            <el-button v-if="query.status !== null || query.orderType !== null || query.keyword" @click="resetFilters">
              清除筛选条件
            </el-button>
          </el-empty>
        </template>
      </el-table>

      <footer class="table-card__foot">
        <span class="wo-text-3">
          共 <b class="wo-num">{{ total }}</b> 条
          <template v-if="query.status !== null">
            · 已按「{{ getStatusLabel(query.status) }}」筛选
          </template>
        </span>
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="sizes, prev, pager, next, jumper"
          background
          @current-change="load"
          @size-change="search"
        />
      </footer>
    </section>
  </div>
</template>

<style scoped lang="scss">
.wo-list {
  display: flex;
  flex-direction: column;
}

// ===========================================================================
// 状态条
// ===========================================================================
.status-bar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: var(--wo-space-4);
}

.status-chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px 6px 10px;
  border: 1px solid var(--wo-hairline);
  border-radius: 999px;
  background: var(--wo-surface);
  cursor: pointer;
  font-family: inherit;
  transition: all var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    border-color: var(--wo-hairline-brand);

    .status-chip__count {
      background: var(--wo-brand-wash-2);
      color: var(--wo-brand-hover);
    }
  }

  // 选中:淡底 + 主色描边,不用实色填充
  &.is-active {
    border-color: var(--wo-brand);
    background: var(--wo-brand-wash);
    box-shadow: 0 0 0 2px var(--wo-brand-ring);

    .status-chip__label {
      color: var(--wo-brand-active);
      font-weight: 600;
    }

    .status-chip__count {
      background: var(--wo-brand);
      color: #fff;
    }
  }

  // 数量为 0 的状态降低存在感,但仍可点
  &.is-empty {
    opacity: 0.5;
  }
}

.status-chip__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--dot);
  flex: none;
}

.status-chip__label {
  font-size: 13px;
  font-weight: 500;
  color: var(--wo-ink-2);
  white-space: nowrap;
}

.status-chip__count {
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  display: inline-grid;
  place-items: center;
  border-radius: 999px;
  background: var(--wo-surface-sunken);
  color: var(--wo-ink-3);
  font-size: 11px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  transition: all var(--wo-dur-fast) var(--wo-ease);
}

// ===========================================================================
// 条件栏
// ===========================================================================
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: var(--wo-space-4);
}

.filters__search {
  width: 280px;

  @media (max-width: 720px) {
    width: 100%;
  }
}

.filters__select {
  width: 148px;

  @media (max-width: 720px) {
    width: calc(50% - 5px);
  }
}

// ===========================================================================
// 表格卡片
// ===========================================================================
.table-card {
  overflow: hidden;
}

.wo-table {
  width: 100%;

  :deep(.el-table__row) {
    cursor: pointer;
  }
}

.order-no {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--wo-ink-3);
  letter-spacing: 0.02em;
}

.order-title {
  font-weight: 500;
  color: var(--wo-ink-1);
}

.table-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wo-space-4);
  flex-wrap: wrap;
  padding: 14px 16px;
  border-top: 1px solid var(--wo-hairline);
  background: var(--wo-surface-raised);
  font-size: 13px;

  b {
    color: var(--wo-ink-1);
    font-weight: 700;
  }
}
</style>
