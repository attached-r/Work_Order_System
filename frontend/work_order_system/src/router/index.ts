/**
 * 路由表与导航守卫
 *
 * 菜单结构直接由这份路由表生成(见 layouts/components/AppSidebar.vue),
 * 所以这里的 meta 既是路由配置,也是菜单配置 —— 只有一份真相。
 *
 * ⚠️ vue-router 5 起 next() 已废弃并会在控制台告警,
 *    守卫一律用「返回值」的写法:
 *      return { name: 'login' }  /  return false  /  return true
 */
import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useUserStore } from '@/stores/user'

// 扩展 meta 的类型,否则 to.meta.xxx 是 unknown(noUncheckedIndexedAccess 下更麻烦)
declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题,用于浏览器标题、面包屑、菜单文案 */
    title?: string
    /** 图标名,取值见 components/icons.ts */
    icon?: string
    /** 访问所需权限码;缺少则从菜单隐藏且禁止直达 */
    perm?: string
    /** 是否在侧边栏隐藏(详情页等) */
    hidden?: boolean
    /** 免登录页面 */
    publicPage?: boolean
    /** 高亮的菜单路径,用于详情页保持父菜单选中态 */
    activeMenu?: string
  }
}

export const routes: RouteRecordRaw[] = [
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/auth/LoginView.vue'),
    meta: { title: '登录', publicPage: true, hidden: true },
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/auth/RegisterView.vue'),
    meta: { title: '注册', publicPage: true, hidden: true },
  },

  {
    path: '/',
    component: () => import('@/layouts/BasicLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'dashboard',
        component: () => import('@/views/dashboard/DashboardView.vue'),
        meta: { title: '工作台', icon: 'Odometer' },
      },
      {
        path: 'workorder',
        name: 'workorder-list',
        component: () => import('@/views/workorder/WorkOrderListView.vue'),
        meta: { title: '工单列表', icon: 'Tickets', perm: 'workorder:query' },
      },
      {
        path: 'workorder/create',
        name: 'workorder-create',
        component: () => import('@/views/workorder/WorkOrderCreateView.vue'),
        meta: { title: '提交工单', icon: 'DocumentAdd', perm: 'workorder:create' },
      },
      {
        path: 'workorder/:id',
        name: 'workorder-detail',
        component: () => import('@/views/workorder/WorkOrderDetailView.vue'),
        meta: {
          title: '工单详情',
          perm: 'workorder:query',
          hidden: true,
          activeMenu: '/workorder',
        },
      },
      {
        path: 'system',
        name: 'system',
        redirect: '/system/user',
        meta: { title: '系统管理', icon: 'Setting', perm: 'user:manage' },
        children: [
          {
            path: 'user',
            name: 'system-user',
            component: () => import('@/views/system/UserListView.vue'),
            meta: { title: '用户管理', icon: 'User', perm: 'user:manage' },
          },
          {
            path: 'role',
            name: 'system-role',
            component: () => import('@/views/system/RoleListView.vue'),
            meta: { title: '角色权限', icon: 'Key', perm: 'user:manage' },
          },
          {
            path: 'dept',
            name: 'system-dept',
            component: () => import('@/views/system/DeptListView.vue'),
            meta: { title: '部门管理', icon: 'OfficeBuilding', perm: 'user:manage' },
          },
        ],
      },
    ],
  },

  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/error/NotFoundView.vue'),
    meta: { title: '页面不存在', publicPage: true, hidden: true },
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  // 切换路由回到顶部;浏览器前进后退时恢复原位置
  scrollBehavior(_to, _from, savedPosition) {
    return savedPosition ?? { top: 0 }
  },
})

const APP_TITLE = '智能工单系统'

router.beforeEach((to) => {
  const userStore = useUserStore()

  // ---- 1. 登录校验 ----
  if (!to.meta.publicPage && !userStore.isLogged) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }

  // ---- 2. 已登录还去登录/注册页,直接送回首页 ----
  if (to.meta.publicPage && userStore.isLogged && (to.name === 'login' || to.name === 'register')) {
    return { name: 'dashboard' }
  }

  // ---- 3. 权限校验:菜单隐藏了不等于直达也被拦住,这里必须再挡一道 ----
  if (to.meta.perm && !userStore.hasPerm(to.meta.perm)) {
    return { name: 'dashboard' }
  }

  return true
})

router.afterEach((to) => {
  document.title = to.meta.title ? `${to.meta.title} · ${APP_TITLE}` : APP_TITLE
})

export default router
