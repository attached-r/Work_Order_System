/**
 * 登录用户与权限
 *
 * 数据全部来自后端:token 由 POST /user/login 下发,用户信息(含角色与权限码)
 * 由 GET /user/me 获取。token 落 localStorage 以便刷新页面不掉登录态,
 * 用户信息也缓存一份,让侧边栏首屏就能按权限渲染、不必等接口回来。
 *
 * 缓存只是「加速」而非「真相」:权限的最终裁决始终在后端(@RequiresPermission),
 * hasPerm() 仅用于决定菜单与按钮的显隐。
 */
import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import {
  fetchMe as apiFetchMe,
  login as apiLogin,
  logout as apiLogout,
  register as apiRegister,
} from '@/api'
import type { RegisterPayload } from '@/api'
import { TOKEN_KEY, USER_KEY } from '@/constants/storage'
import type { UserVO } from '@/types/domain'

export const useUserStore = defineStore('user', () => {
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

  function reset() {
    token.value = ''
    user.value = null
    persist()
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

  /** 拉取当前用户信息(含角色与权限码)并写入缓存 */
  async function fetchMe(): Promise<void> {
    if (!token.value) {
      // 没有 token 就没有"当前用户"可言,顺手把可能残留的缓存清掉
      user.value = null
      persist()
      return
    }
    user.value = await apiFetchMe()
    persist()
  }

  /**
   * 保证用户信息已就绪,供路由守卫在受保护页面渲染前调用。
   * 已有缓存时直接返回 —— 首屏不为此多等一次往返;缓存缺失(如手动清了
   * localStorage 里的 wo_user)时才真正发起请求。
   */
  async function ensureLoaded(): Promise<void> {
    if (!token.value || user.value) return
    await fetchMe()
  }

  async function login(username: string, password: string): Promise<void> {
    // 先拿 token,再用它换用户信息 —— 权限码只有 /user/me 会给
    token.value = await apiLogin({ username, password })
    persist()

    try {
      await fetchMe()
    } catch (e) {
      // token 拿到了却查不到用户,说明这个登录态没用,别留在本地
      reset()
      throw e
    }
  }

  /** 注册:成功后不直接登录,由页面引导用户去登录页 */
  async function register(payload: RegisterPayload): Promise<void> {
    await apiRegister(payload)
  }

  async function logout(): Promise<void> {
    try {
      // 让服务端 token 立即失效,而不是只清本地缓存
      if (token.value) await apiLogout()
    } catch {
      // 网络异常也不该把用户困在登录态里,本地照清不误
    } finally {
      reset()
    }
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
    ensureLoaded,
    logout,
  }
})
