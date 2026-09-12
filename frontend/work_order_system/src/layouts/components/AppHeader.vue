<script setup lang="ts">
/**
 * 顶栏
 *
 * 左:折叠按钮 + 面包屑。右:当前用户。
 * 顶栏刻意做得很轻 —— 半透明白底 + 一条发丝线,不用阴影,
 * 让它"浮"在内容之上而不是压在内容上。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
// 裸图标名不会被 unplugin 的 resolver 解析(只认 ElXxx 前缀),必须显式导入
import { ArrowDown, Expand, Fold, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { getRoleName } from '@/constants/role'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const appStore = useAppStore()

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
  ElMessage.success('已退出登录')
  router.push({ name: 'login' })
}

function handleCommand(command: string) {
  if (command === 'logout') void handleLogout()
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
  transition:
    background-color var(--wo-dur-fast) var(--wo-ease),
    color var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    background: var(--wo-brand-wash);
    color: var(--wo-brand-hover);
  }
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
