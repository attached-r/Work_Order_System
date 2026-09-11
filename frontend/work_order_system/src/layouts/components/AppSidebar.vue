<script setup lang="ts">
/**
 * 侧边导航
 *
 * 菜单直接从路由表推导,不维护第二份菜单配置 —— 加路由即加菜单。
 * 过滤规则:meta.hidden 的不要,meta.perm 无权限的不要。
 */
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { routes } from '@/router'
import { useUserStore } from '@/stores/user'
import { useAppStore } from '@/stores/app'
import { resolveIcon } from '@/components/icons'

interface MenuItem {
  /** 完整路径,用作 el-menu 的 index */
  path: string
  title: string
  icon?: string
  children?: MenuItem[]
}

/** 把 '/a' 与相对段 'b' 拼成 '/a/b',并处理绝对段 */
function joinPath(parent: string, child: string): string {
  if (child.startsWith('/')) return child
  return `${parent.replace(/\/$/, '')}/${child}`
}

/** 把一个路由节点转成菜单项;返回 null 表示不该出现在菜单里 */
function toMenuItem(route: RouteRecordRaw, parentPath: string): MenuItem | null {
  if (route.meta?.hidden) return null

  const rawPath = route.path
  const fullPath = joinPath(parentPath, rawPath)

  const children = (route.children ?? [])
    .map((c) => toMenuItem(c, fullPath))
    .filter((c): c is MenuItem => c !== null)

  // 只有重定向、没有实际页面的中间节点,如果子项全被过滤掉,自己也不该出现
  const hasComponent = Boolean(route.component)
  if (!hasComponent && children.length === 0) return null

  return {
    path: fullPath === '/' ? '/dashboard' : fullPath,
    title: route.meta?.title ?? '',
    icon: route.meta?.icon,
    // 有真实组件又能直接点开的,才当作叶子节点
    children: children.length > 0 && !(hasComponent && rawPath !== '/') ? children : undefined,
  }
}

const menuItems = computed<MenuItem[]>(() => {
  const userStore = useUserStore()

  const layoutRoute = routes.find((r) => r.path === '/')
  if (!layoutRoute?.children) return []

  return layoutRoute.children
    .filter((r) => !r.meta?.hidden)
    .filter((r) => !r.meta?.perm || userStore.hasPerm(r.meta.perm))
    .map((r) => toMenuItem(r, '/'))
    .filter((m): m is MenuItem => m !== null)
})

const route = useRoute()
const appStore = useAppStore()

/** 详情页等非菜单页,靠 meta.activeMenu 保持父菜单高亮 */
const activeMenu = computed(() => route.meta.activeMenu ?? route.path)
</script>

<template>
  <aside class="sidebar" :class="{ 'is-collapsed': appStore.sidebarCollapsed }">
    <!-- 品牌区:一个几何标记 + 字标 -->
    <RouterLink to="/dashboard" class="brand">
      <span class="brand__mark" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
          <rect x="2.5" y="2.5" width="19" height="19" rx="5.5" stroke="currentColor" stroke-width="1.9" />
          <path d="M7.5 12.2l3 3 6-6.4" stroke="currentColor" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </span>
      <Transition name="brand-fade">
        <span v-if="!appStore.sidebarCollapsed" class="brand__text">
          <b>智能工单</b>
          <i>Work Order</i>
        </span>
      </Transition>
    </RouterLink>

    <el-scrollbar class="sidebar__scroll">
      <el-menu
        :default-active="activeMenu"
        :collapse="appStore.sidebarCollapsed"
        :collapse-transition="false"
        unique-opened
        router
      >
        <template v-for="item in menuItems" :key="item.path">
          <!-- 有子菜单 -->
          <el-sub-menu v-if="item.children" :index="item.path">
            <template #title>
              <el-icon v-if="resolveIcon(item.icon)">
                <component :is="resolveIcon(item.icon)" />
              </el-icon>
              <span>{{ item.title }}</span>
            </template>
            <el-menu-item v-for="child in item.children" :key="child.path" :index="child.path">
              <el-icon v-if="resolveIcon(child.icon)">
                <component :is="resolveIcon(child.icon)" />
              </el-icon>
              <span>{{ child.title }}</span>
            </el-menu-item>
          </el-sub-menu>

          <!-- 叶子菜单 -->
          <el-menu-item v-else :index="item.path">
            <el-icon v-if="resolveIcon(item.icon)">
              <component :is="resolveIcon(item.icon)" />
            </el-icon>
            <template #title>
              <span>{{ item.title }}</span>
            </template>
          </el-menu-item>
        </template>
      </el-menu>
    </el-scrollbar>

    <!-- 底部版本信息:折叠时隐藏,避免挤压 -->
    <div v-if="!appStore.sidebarCollapsed" class="sidebar__foot">
      <span class="wo-eyebrow">v1.0 · 开发中</span>
    </div>
  </aside>
</template>

<style scoped lang="scss">
.sidebar {
  width: var(--wo-sidebar-w);
  flex: none;
  display: flex;
  flex-direction: column;
  // 极淡的蓝白渐变,比纯白更有空间感但不喧宾夺主
  background: linear-gradient(180deg, #fbfdff 0%, #f7fafe 100%);
  border-right: 1px solid var(--wo-hairline);
  transition: width var(--wo-dur) var(--wo-ease);
}

.sidebar.is-collapsed {
  width: var(--wo-sidebar-w-collapsed);
}

// ---- 品牌区 ----
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  height: var(--wo-header-h);
  padding: 0 16px;
  flex: none;
  border-bottom: 1px solid var(--wo-hairline);
  color: var(--wo-ink-1);
  overflow: hidden;

  &:hover {
    color: var(--wo-ink-1);
  }
}

.brand__mark {
  width: 32px;
  height: 32px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: #fff;
  background: linear-gradient(145deg, #4c85f5 0%, var(--wo-brand) 55%, #1e56cf 100%);
  box-shadow: 0 2px 6px rgba(47, 111, 237, 0.28);
}

.brand__text {
  display: flex;
  flex-direction: column;
  line-height: 1.15;
  white-space: nowrap;

  b {
    font-size: 15px;
    font-weight: 700;
    letter-spacing: -0.01em;
  }

  i {
    font-style: normal;
    font-size: 10px;
    font-weight: 600;
    letter-spacing: 0.12em;
    text-transform: uppercase;
    color: var(--wo-ink-4);
  }
}

.brand-fade-enter-active,
.brand-fade-leave-active {
  transition: opacity var(--wo-dur-fast) var(--wo-ease);
}
.brand-fade-enter-from,
.brand-fade-leave-to {
  opacity: 0;
}

// ---- 菜单区 ----
.sidebar__scroll {
  flex: 1;
  min-height: 0;
  padding: 10px 0;
}

.sidebar__foot {
  flex: none;
  padding: 12px 18px 16px;
  border-top: 1px solid var(--wo-hairline);
}

// 折叠态下 el-menu 会给 item 加额外 padding,统一压掉让图标居中
.sidebar :deep(.el-menu--collapse) {
  .el-menu-item,
  .el-sub-menu__title {
    padding: 0 !important;
    padding-left: 0 !important;
    justify-content: center;
    margin-left: 10px;
    margin-right: 10px;
  }
}
</style>
