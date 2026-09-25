<script setup lang="ts">
/**
 * 顶栏
 *
 * 左:折叠按钮 + 面包屑。右:消息铃铛 + 当前用户。
 * 顶栏刻意做得很轻 —— 半透明白底 + 一条发丝线,不用阴影,
 * 让它"浮"在内容之上而不是压在内容上。
 *
 * 未读数的轮询挂在这里:顶栏自 BasicLayout 挂载起就一直存在,
 * 起停放在这个组件里最省事,也不用去改布局层。
 */
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
// 裸图标名不会被 unplugin 的 resolver 解析(只认 ElXxx 前缀),必须显式导入
import { ArrowDown, Bell, Expand, Fold, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { useNotificationStore } from '@/stores/notification'
import { getRoleName } from '@/constants/role'
import NotifyTypeTag from '@/components/NotifyTypeTag.vue'
import { formatRelative } from '@/utils/datetime'
import type { NotificationVO } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const appStore = useAppStore()
const notificationStore = useNotificationStore()

/**
 * 悬浮层的显隐由自己接管,不单靠 trigger="click" ——
 * 点「查看全部」会路由跳转,而顶栏在跳转后并不卸载,不主动关就留在那儿了。
 */
const inboxOpen = ref(false)

onMounted(() => notificationStore.startPolling())
onUnmounted(() => notificationStore.stopPolling())

/** 面包屑:取匹配到的路由链上的 title */
const crumbs = computed(() =>
  route.matched
    .filter((r) => r.meta?.title)
    .map((r) => ({ title: r.meta.title as string, name: r.name })),
)

/** 角色中文名,取第一个角色展示 */
const roleLabel = computed(() => {
  const first = userStore.roles[0]
  return first ? getRoleName(first) : '—'
})

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗?', '退出登录', {
      confirmButtonText: '退出',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return // 用户点了取消
  }

  await userStore.logout()
  // 清掉未读数并停掉轮询 —— 不然上一个账号的红点会跟着下一个账号
  notificationStore.reset()
  ElMessage.success('已退出登录')
  router.push({ name: 'login' })
}

function handleCommand(command: string) {
  if (command === 'logout') void handleLogout()
}

// ---- 消息铃铛 ----

/** 展开时才拉列表:没点开就不该产生请求 */
function handleInboxShow() {
  void notificationStore.loadRecent()
}

async function handleMarkAllRead() {
  try {
    const marked = await notificationStore.markAllRead()
    ElMessage.success(marked > 0 ? `已将 ${marked} 条消息标记为已读` : '没有未读消息')
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败')
  }
}

/** 点一条:标已读 → 关掉悬浮层 → 有关联工单就跳过去 */
async function openNotification(n: NotificationVO) {
  try {
    await notificationStore.markRead(n.id, n.readFlag === 0)
  } catch {
    // 标记失败不拦住跳转 —— 用户要看的是工单
  }
  inboxOpen.value = false

  const id = n.bizId ? Number(n.bizId) : Number.NaN
  if (!Number.isFinite(id) || id <= 0) {
    ElMessage.info('这条消息没有关联的工单')
    return
  }
  router.push({ name: 'workorder-detail', params: { id } })
}

function goInbox() {
  inboxOpen.value = false
  router.push({ name: 'notification-list' })
}
</script>

<template>
  <header class="topbar">
    <div class="topbar__left">
      <button
        class="icon-btn"
        type="button"
        :title="appStore.sidebarCollapsed ? '展开菜单' : '收起菜单'"
        @click="appStore.toggleSidebar()"
      >
        <el-icon :size="18">
          <component :is="appStore.sidebarCollapsed ? Expand : Fold" />
        </el-icon>
      </button>

      <el-breadcrumb separator="/">
        <el-breadcrumb-item v-for="c in crumbs" :key="String(c.name)">{{ c.title }}</el-breadcrumb-item>
      </el-breadcrumb>
    </div>

    <div class="topbar__right">
      <!-- ============ 消息铃铛 ============ -->
      <el-popover
        v-model:visible="inboxOpen"
        trigger="click"
        placement="bottom-end"
        :width="360"
        :show-arrow="false"
        popper-class="wo-notify-pop"
        @show="handleInboxShow"
      >
        <template #reference>
          <!-- 红点自己画而不是用 el-badge:后者的默认色是 --el-color-danger(#f56c6c),
               在这个「所有红色都出自 tokens」的界面里会格外扎眼。自己画还能顺手
               把 99+ 的截断写清楚。 -->
          <button class="icon-btn" type="button" title="消息通知">
            <el-icon :size="18"><Bell /></el-icon>
            <span v-if="notificationStore.hasUnread" class="icon-btn__dot">
              {{ notificationStore.unreadCount > 99 ? '99+' : notificationStore.unreadCount }}
            </span>
          </button>
        </template>

        <div class="inbox-pop">
          <header class="inbox-pop__head">
            <span class="wo-eyebrow">Unread</span>
            <b>未读消息</b>
            <span v-if="notificationStore.hasUnread" class="inbox-pop__count wo-num">
              {{ notificationStore.unreadCount }}
            </span>
          </header>

          <div v-loading="notificationStore.loadingRecent" class="inbox-pop__body">
            <ul v-if="notificationStore.recent.length" class="inbox-pop__list">
              <li
                v-for="n in notificationStore.recent"
                :key="n.id"
                class="inbox-note"
                @click="openNotification(n)"
              >
                <div class="inbox-note__head">
                  <NotifyTypeTag :type="n.notifyType" :desc="n.notifyTypeDesc" size="small" />
                  <time class="inbox-note__time wo-num" :datetime="n.createTime">
                    {{ formatRelative(n.createTime) }}
                  </time>
                </div>
                <p class="inbox-note__title">{{ n.title }}</p>
                <p class="inbox-note__text">{{ n.content }}</p>
              </li>
            </ul>

            <el-empty
              v-else-if="!notificationStore.loadingRecent"
              :image-size="64"
              description="暂无未读消息"
            />
          </div>

          <footer class="inbox-pop__foot">
            <el-button text :disabled="!notificationStore.hasUnread" @click="handleMarkAllRead">
              全部已读
            </el-button>
            <el-button text type="primary" @click="goInbox">查看全部</el-button>
          </footer>
        </div>
      </el-popover>

      <!-- 用户在 mock 阶段固定展示,接入后端后由 /user/me 填充 -->
      <el-dropdown trigger="click" @command="handleCommand">
        <div class="account">
          <span class="account__avatar">{{ userStore.initial }}</span>
          <span class="account__meta">
            <b>{{ userStore.displayName }}</b>
            <i>{{ roleLabel }}</i>
          </span>
          <el-icon class="account__caret" :size="12"><ArrowDown /></el-icon>
        </div>

        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item disabled>
              <span class="wo-text-3">{{ userStore.user?.username ?? '—' }}</span>
            </el-dropdown-item>
            <el-dropdown-item divided command="logout">
              <el-icon><SwitchButton /></el-icon>
              退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
  </header>
</template>

<style scoped lang="scss">
.topbar {
  height: var(--wo-header-h);
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wo-space-4);
  padding: 0 20px;
  // 半透明 + 背景模糊:内容滚动到底下时会透出来一点,是"轻"的关键
  background: rgba(255, 255, 255, 0.82);
  backdrop-filter: blur(12px) saturate(1.4);
  border-bottom: 1px solid var(--wo-hairline);
  position: sticky;
  top: 0;
  z-index: 10;
}

.topbar__left,
.topbar__right {
  display: flex;
  align-items: center;
  gap: var(--wo-space-3);
  min-width: 0;
}

.icon-btn {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: var(--wo-radius-sm);
  background: transparent;
  color: var(--wo-ink-2);
  cursor: pointer;
  // 未读红点是绝对定位挂在这上面的
  position: relative;
  transition:
    background-color var(--wo-dur-fast) var(--wo-ease),
    color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: var(--wo-brand-wash);
    color: var(--wo-brand-hover);
  }
}

// 未读红点:取 tokens 里的红(与「已驳回」同一个色),外圈一道白边把它和铃铛隔开
.icon-btn__dot {
  position: absolute;
  top: -2px;
  right: -2px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  display: inline-grid;
  place-items: center;
  border-radius: 999px;
  background: var(--wo-st-5-dot);
  box-shadow: 0 0 0 2px var(--wo-surface);
  color: #fff;
  font-size: 10px;
  font-weight: 700;
  line-height: 1;
  font-variant-numeric: tabular-nums;
  pointer-events: none;
}

// ---- 账号区 ----
.account {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 4px 8px 4px 4px;
  border-radius: 999px;
  cursor: pointer;
  outline: none;
  transition: background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: var(--wo-brand-wash);
  }
}

.account__avatar {
  width: 30px;
  height: 30px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(145deg, #5b90f7 0%, var(--wo-brand) 100%);
  box-shadow: 0 1px 3px rgba(47, 111, 237, 0.3);
}

.account__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.2;

  b {
    font-size: 13px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }

  i {
    font-style: normal;
    font-size: 11px;
    color: var(--wo-ink-3);
  }
}

.account__caret {
  color: var(--wo-ink-4);
}

// ---- 消息悬浮层 ----
// 内容虽然被 teleport 到 body,但它们都带本组件的作用域属性,scoped 规则照样命中;
// 只有 .el-popover 那层外壳吃不到,所以去 element-override.scss 里覆写它的内边距。
.inbox-pop {
  display: flex;
  flex-direction: column;
}

.inbox-pop__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 13px 16px 10px;
  border-bottom: 1px solid var(--wo-hairline);

  b {
    font-size: 14px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }
}

.inbox-pop__count {
  margin-left: auto;
  min-width: 20px;
  height: 18px;
  padding: 0 6px;
  display: inline-grid;
  place-items: center;
  border-radius: 999px;
  background: var(--wo-brand);
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.inbox-pop__body {
  // 空态与加载态也要占住高度,否则 loading 的转圈会被裁没
  min-height: 132px;
  max-height: 380px;
  overflow-y: auto;
}

.inbox-pop__list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.inbox-note {
  padding: 11px 16px;
  cursor: pointer;
  transition: background-color var(--wo-dur-fast) var(--wo-ease);

  &:not(:last-child) {
    border-bottom: 1px solid var(--wo-hairline);
  }

  &:hover {
    background: var(--wo-brand-wash);
  }
}

.inbox-note__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.inbox-note__time {
  flex: none;
  font-size: 11px;
  color: var(--wo-ink-4);
}

.inbox-note__title {
  margin-top: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--wo-ink-1);
  line-height: 1.45;
}

.inbox-note__text {
  margin-top: 2px;
  font-size: 12px;
  color: var(--wo-ink-3);
  line-height: 1.6;
  // 层里只留两行,详细内容点进工单看
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.inbox-pop__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 10px 10px;
  border-top: 1px solid var(--wo-hairline);
  background: var(--wo-surface-raised);
}

// 窄屏隐藏姓名文字,只留头像
@media (max-width: 720px) {
  .account__meta {
    display: none;
  }
  .topbar :deep(.el-breadcrumb) {
    display: none;
  }
}
</style>
