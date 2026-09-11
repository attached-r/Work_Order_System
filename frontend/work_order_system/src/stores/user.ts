/**
 * 登录用户与权限
 *
 * 现阶段数据来自 mock,登录/登出是纯前端模拟。
 * 接入后端时要改的只有三个地方(已用 TODO(api) 标注):
 *   1. login()  -> POST /user/login 拿 token
 *   2. fetchMe() -> GET /user/me 拿用户信息与权限
 *   3. logout() -> POST /user/logout
 * 其余组件只依赖 hasPerm(),所以替换过程对页面透明。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { MOCK_ACCOUNTS } from '@/mock'
import type { UserVO } from '@/types/domain'

const TOKEN_KEY = 'wo_token'
const USER_KEY = 'wo_user'

export const useUserStore = defineStore('user', () => {
  // token 落 localStorage,刷新页面不掉登录态
  const token = ref<string>(localStorage.getItem(TOKEN_KEY) ?? '')
  const user = ref<UserVO | null>(readCachedUser())

  function readCachedUser(): UserVO | null {
    const raw = localStorage.getItem(USER_KEY)
    if (!raw) return null
    try {
      return JSON.parse(raw) as UserVO
    } catch {
      // 缓存被手改坏了就丢弃,不要让它阻塞启动
      localStorage.removeItem(USER_KEY)
      return null
    }
  }

  function persist() {
    if (token.value) localStorage.setItem(TOKEN_KEY, token.value)
    else localStorage.removeItem(TOKEN_KEY)

    if (user.value) localStorage.setItem(USER_KEY, JSON.stringify(user.value))
    else localStorage.removeItem(USER_KEY)
  }

  const isLogged = computed(() => Boolean(token.value))
  const perms = computed<string[]>(() => user.value?.perms ?? [])
  const roles = computed<string[]>(() => user.value?.roles ?? [])

  /** 是否拥有某个权限码;后端 @RequiresPermission 的镜像,仅用于控制按钮/菜单显隐 */
  function hasPerm(code: string | null | undefined): boolean {
    if (!code) return true
    return perms.value.includes(code)
  }

  /** 是否拥有其中任意一个权限 */
  function hasAnyPerm(codes: string[]): boolean {
    if (codes.length === 0) return true
    return codes.some((c) => perms.value.includes(c))
  }

  const displayName = computed(() => user.value?.realName || user.value?.username || '未登录')

  /** 姓名首字,用于头像占位 */
  const initial = computed(() => displayName.value.slice(0, 1))

  // TODO(api): 换成 POST /user/login
  async function login(username: string, password: string): Promise<void> {
    // 模拟网络往返,让 loading 态在页面上真实可见
    await new Promise((r) => setTimeout(r, 420))

    const account = MOCK_ACCOUNTS.find((a) => a.username === username.trim())
    if (!account || account.password !== password) {
      throw new Error('用户名或密码错误')
    }
    if (account.user.status !== 1) {
      throw new Error('账号已被停用,请联系管理员')
    }

    token.value = `mock-token-${account.user.userId}-${Date.now()}`
    user.value = account.user
    persist()
  }

  // TODO(api): 换成 POST /user/register
  async function register(payload: { username: string; password: string; realName: string }): Promise<void> {
    await new Promise((r) => setTimeout(r, 420))
    if (MOCK_ACCOUNTS.some((a) => a.username === payload.username.trim())) {
      throw new Error('该用户名已被注册')
    }
    // 注册不直接登录,回到登录页让用户手动登一次,流程更清晰
  }

  // TODO(api): 换成 GET /user/me
  async function fetchMe(): Promise<void> {
    if (!token.value) return
    await new Promise((r) => setTimeout(r, 120))
    // mock 阶段 user 已在 login 时写入并缓存,这里只保证刷新后有值
    if (!user.value) user.value = readCachedUser()
  }

  // TODO(api): 换成 POST /user/logout
  async function logout(): Promise<void> {
    token.value = ''
    user.value = null
    persist()
  }

  return {
    token,
    user,
    isLogged,
    perms,
    roles,
    displayName,
    initial,
    hasPerm,
    hasAnyPerm,
    login,
    register,
    fetchMe,
    logout,
  }
})
