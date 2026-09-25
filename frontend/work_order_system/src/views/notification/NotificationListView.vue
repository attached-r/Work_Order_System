<script setup lang="ts">
/**
 * 消息通知(收件箱)
 *
 * 与工单列表同一套骨架:页面标题 + 一排带数量的分段筛选 + 内容区 + 底部翻页条。
 * 差别只在中段 —— 收件箱里一行是一段"事件",不是一条"记录",
 * 所以用列表(list)而不是表格(table):标题与正文需要换行展开,
 * 表格的行高与列宽在这里只会互相打架。
 *
 * 两个刻意不做的动作:
 *   - 不做"标为未读"。后端只有 {id}/read 与 read-all 两个写接口,没有反向的;
 *   - 不做删除。后端没有删除接口,消息是审计线索,不该在前端假装能删。
 *
 * 点一行 = 标已读 + 有关联工单就跳过去。跳转失败(权限不足被守卫弹回工作台)是正常结果,
 * 因为通知的收件人未必有 workorder:query。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
// 裸图标名不会被 resolver 解析(只认 ElXxx 前缀),必须显式导入
import { CircleCheck, Refresh, Right } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import NotifyTypeTag from '@/components/NotifyTypeTag.vue'
import { READ_FLAG_OPTIONS } from '@/constants/notification'
import { pageNotifications } from '@/api'
import { formatRelative } from '@/utils/datetime'
import { useNotificationStore } from '@/stores/notification'
import type { NotificationVO } from '@/types/domain'

const router = useRouter()
const route = useRoute()
const notificationStore = useNotificationStore()

const loading = ref(false)
const rows = ref<NotificationVO[]>([])
const total = ref(0)

const query = reactive({
  current: 1,
  size: 10,
  /** null = 全部,0 = 未读,1 = 已读 */
  readFlag: null as number | null,
})

/**
 * 不带筛选时的总条数。
 * size=1 只为拿 total —— 这里要的是计数,不是内容。
 */
const allTotal = ref(0)

/**
 * 未读总数直接读 store,不自己再存一份:
 * 顶栏红点和这里筛选条上的数字必须永远是同一个来源,各存一份迟早对不上。
 */
const unreadTotal = computed(() => notificationStore.unreadCount)

/** 筛选条的三个档位,各自带数量。已读是一道减法 —— 后端不单独给这个计数 */
const chips = computed(() =>
  READ_FLAG_OPTIONS.map((opt) => ({
    value: opt.value,
    label: opt.label,
    count:
      opt.value === 0
        ? unreadTotal.value
        : opt.value === 1
          ? Math.max(0, allTotal.value - unreadTotal.value)
          : allTotal.value,
  })),
)

function isUnread(n: NotificationVO): boolean {
  return n.readFlag === 0
}

/** bizId 是工单ID的字符串形式。空值/脏值一律当作"没有关联工单",别跳到一个 404 */
function workOrderIdOf(n: NotificationVO): number | null {
  if (!n.bizId) return null
  const id = Number(n.bizId)
  return Number.isFinite(id) && id > 0 ? id : null
}

async function load() {
  loading.value = true
  try {
    const res = await pageNotifications({
      current: query.current,
      size: query.size,
      readFlag: query.readFlag,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '消息加载失败')
  } finally {
    loading.value = false
  }
}

/**
 * 刷新筛选条上的数字,顺带把顶栏红点校准一次 —— refreshUnread 只发一个请求,
 * 两个数字一起更新,不会出现"这里显示 3、顶栏显示 2"。
 */
async function loadStats() {
  try {
    const [pageRes] = await Promise.all([
      pageNotifications({ current: 1, size: 1 }),
      notificationStore.refreshUnread(),
    ])
    allTotal.value = pageRes.total
  } catch {
    // 筛选条只是辅助,拿不到就保持原值,不打断列表
  }
}

/** 首屏与"全部已读"后需要两边都重查;切筛选只查列表,数字不动(理由同工单列表) */
async function refreshBoth() {
  await Promise.all([load(), loadStats()])
}

function pickReadFlag(value: number | null) {
  query.readFlag = value
  query.current = 1
  void load()
}

/** 改每页条数:回到第一页重查。数字不动,不用惊动 loadStats */
function changeSize() {
  query.current = 1
  void load()
}

/**
 * 点开一条消息。
 *
 * 先标已读再跳转:跳转会把整页卸载,标已读的请求必须在离开前发出去。
 * 标记失败不阻拦跳转 —— 用户要看的是工单,不是已读状态。
 */
async function openNotification(n: NotificationVO) {
  if (isUnread(n)) {
    try {
      await notificationStore.markRead(n.id, true)
      n.readFlag = 1
    } catch {
      // 见上
    }
    void loadStats()
  }

  const orderId = workOrderIdOf(n)
  if (orderId === null) {
    ElMessage.info('这条消息没有关联的工单')
    return
  }
  router.push({ name: 'workorder-detail', params: { id: orderId } })
}

/**
 * 行内的「标为已读」。
 *
 * 就地改 readFlag 而不是重查整页:重查会让这一行在"未读"筛选下当场消失,
 * 列表跳一下反而让人怀疑自己点错了;留在原地变成已读态更符合直觉。
 */
async function markOne(n: NotificationVO) {
  if (!isUnread(n)) return
  try {
    await notificationStore.markRead(n.id, true)
    n.readFlag = 1
    void loadStats()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '标记失败')
  }
}

async function markAll() {
  try {
    const marked = await notificationStore.markAllRead()
    ElMessage.success(marked > 0 ? `已将 ${marked} 条消息标记为已读` : '没有未读消息')
    // 页面上的行要跟着变成已读态,数字也要重算
    await refreshBoth()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

onMounted(() => {
  // 支持从顶栏红点带 ?readFlag=0 跳进来,直接落到"未读"
  const f = route.query.readFlag
  if (typeof f === 'string' && f !== '') {
    const n = Number(f)
    if (n === 0 || n === 1) query.readFlag = n
  }
  void refreshBoth()
})

/** 空态文案跟着筛选走,比一句笼统的"暂无数据"有用 */
const emptyText = computed(() =>
  query.readFlag === 0 ? '没有未读消息' : query.readFlag === 1 ? '还没有已读消息' : '收件箱是空的',
)
</script>

<template>
  <div class="wo-inbox">
    <PageHeader
      eyebrow="Notifications"
      title="消息通知"
      description="工单流转到你这儿时的站内信。点开一条会同时标记已读,并跳到对应的工单。"
    >
      <template #actions>
        <el-button @click="refreshBoth">
          <el-icon><Refresh /></el-icon>
          刷新
        </el-button>
        <el-button type="primary" :disabled="!notificationStore.hasUnread" @click="markAll">
          <el-icon><CircleCheck /></el-icon>
          全部已读
        </el-button>
      </template>
    </PageHeader>

    <!-- ============ 读取状态筛选 ============ -->
    <section class="status-bar" aria-label="按读取状态筛选">
      <button
        v-for="c in chips"
        :key="String(c.value)"
        type="button"
        class="status-chip"
        :class="{ 'is-active': query.readFlag === c.value, 'is-empty': c.count === 0 }"
        @click="pickReadFlag(c.value)"
      >
        <span class="status-chip__label">{{ c.label }}</span>
        <span class="status-chip__count">{{ c.count }}</span>
      </button>
    </section>

    <!-- ============ 收件箱 ============ -->
    <section v-loading="loading" class="feed wo-card">
      <ul v-if="rows.length" class="feed__list">
        <li
          v-for="n in rows"
          :key="n.id"
          class="note"
          :class="{ 'is-unread': isUnread(n) }"
          @click="openNotification(n)"
        >
          <div class="note__head">
            <NotifyTypeTag :type="n.notifyType" :desc="n.notifyTypeDesc" size="small" />
            <span v-if="n.orderNo" class="note__order wo-num">{{ n.orderNo }}</span>
            <time class="note__time wo-num" :datetime="n.createTime">
              {{ formatRelative(n.createTime) }}
            </time>
          </div>

          <h3 class="note__title">{{ n.title }}</h3>
          <p class="note__text">{{ n.content }}</p>

          <div class="note__foot">
            <el-button v-if="isUnread(n)" text size="small" @click.stop="markOne(n)">
              标为已读
            </el-button>
            <span v-if="workOrderIdOf(n) !== null" class="note__go">
              查看工单
              <el-icon :size="12"><Right /></el-icon>
            </span>
          </div>
        </li>
      </ul>

      <el-empty v-else-if="!loading" :description="emptyText">
        <el-button v-if="query.readFlag !== null" @click="pickReadFlag(null)">查看全部消息</el-button>
      </el-empty>

      <footer class="feed__foot">
        <span class="wo-text-3">
          共 <b class="wo-num">{{ total }}</b> 条
          <template v-if="query.readFlag !== null">
            · 已按「{{ query.readFlag === 0 ? '未读' : '已读' }}」筛选
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
          @size-change="changeSize"
        />
      </footer>
    </section>
  </div>
</template>

<style scoped lang="scss">
.wo-inbox {
  display: flex;
  flex-direction: column;
}

// ===========================================================================
// 读取状态筛选(与工单列表的状态条同一套药丸样式)
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
  padding: 6px 12px 6px 12px;
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

  &.is-empty {
    opacity: 0.5;
  }
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
// 收件箱
// ===========================================================================
.feed {
  overflow: hidden;
  // 空态与加载态下卡片不能塌成一条线,否则 loading 的转圈会被裁掉
  min-height: 240px;
}

.feed__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.note {
  position: relative;
  padding: 14px 18px 12px;
  cursor: pointer;
  // 已读:略微沉下去一点。差别刻意做得很小 —— 这个设计系统里靠字重和竖条区分,
  // 不靠底色,大面积色块在列表里会糊成一片
  background: var(--wo-surface-raised);
  transition: background-color var(--wo-dur-fast) var(--wo-ease);

  &:not(:last-child) {
    border-bottom: 1px solid var(--wo-hairline);
  }

  // 左侧竖条:未读是常驻的主色,已读只在悬浮时浮现(与表格行的悬浮提示同一手法)
  &::before {
    content: '';
    position: absolute;
    left: 0;
    top: 0;
    bottom: 0;
    width: 3px;
    background: transparent;
    transition: background-color var(--wo-dur-fast) var(--wo-ease);
  }

  &:hover {
    background: var(--wo-surface);

    &::before {
      background: var(--wo-hairline-brand);
    }
  }

  &.is-unread {
    background: var(--wo-surface);

    &::before {
      background: var(--wo-brand);
    }

    .note__title {
      color: var(--wo-ink-1);
      font-weight: 600;
    }
  }
}

.note__head {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.note__order {
  font-size: 12px;
  font-weight: 600;
  color: var(--wo-ink-3);
  letter-spacing: 0.02em;
}

.note__time {
  margin-left: auto;
  flex: none;
  font-size: 12px;
  color: var(--wo-ink-4);
}

.note__title {
  margin-top: 7px;
  font-size: 14.5px;
  font-weight: 500;
  color: var(--wo-ink-2);
  line-height: 1.45;
}

.note__text {
  margin-top: 3px;
  font-size: 13px;
  color: var(--wo-ink-3);
  line-height: 1.65;
  // 正文最多三行,再长就截断 —— 收件箱要的是扫读,完整内容在工单详情里
  display: -webkit-box;
  -webkit-line-clamp: 3;
  line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.note__foot {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: var(--wo-space-3);
  margin-top: 4px;
  // 行高留一点,免得按钮 hover 时把整行撑开
  min-height: 26px;
}

// 「标为已读」只在悬浮时才出现,平时让位给内容
.note__foot :deep(.el-button) {
  opacity: 0;
  transition: opacity var(--wo-dur-fast) var(--wo-ease);
}

.note:hover .note__foot :deep(.el-button) {
  opacity: 1;
}

.note__go {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  font-size: 12px;
  font-weight: 500;
  color: var(--wo-ink-4);
  transition: color var(--wo-dur-fast) var(--wo-ease);
}

.note:hover .note__go {
  color: var(--wo-brand);
}

.feed__foot {
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
