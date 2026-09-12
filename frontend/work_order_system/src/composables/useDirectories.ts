/**
 * 目录(directory)
 *
 * 后端的工单接口只回传外键 id,不回名字 —— 凡是展示人名 / 部门名 / 角色名的地方,
 * 都得自己备一份 id -> 记录 的映射表。
 *
 * 这几张小表在一次会话里基本不变,于是统一收敛成同一套「模块级单例 + 懒加载」:
 *   - 单例:工单列表 / 详情 / 部门 / 用户管理几个页面共享同一次请求,而不是各拉一遍;
 *   - 懒加载:第一次 ensure() 才发请求,且并发调用合并成同一个;
 *   - 读得到才有:拿不到时目录保持为空,调用方回退显示「用户 #id / 部门 #id」,
 *     页面不因此报错。
 *
 * 三份目录的权限门槛并不相同,`canRead` 因此分别判定:
 *   - 用户 / 部门:**登录即可**(GET /user/directory、GET /department/directory),
 *     这是专门为"能看工单但没有 user:manage"的角色补的接口 ——
 *     否则派单人取不到处理人候选,派单功能直接不可用;
 *   - 角色:仍要求 user:manage(GET /role/list 没放宽,也用不着放宽)。
 */
import { computed, ref } from 'vue'
import type { ComputedRef, Ref } from 'vue'
import { listDepartmentDirectory, listRoles, listUserDirectory } from '@/api'
import { useUserStore } from '@/stores/user'
import type { DepartmentBriefVO, Role, UserBriefVO } from '@/types/domain'

interface Directory<T> {
  ensure: (canRead: boolean) => Promise<void>
  invalidate: () => void
  items: Ref<T[]>
}

/**
 * @param fetchList 拉取全量列表的函数
 */
function createDirectory<T>(fetchList: () => Promise<T[]>): Directory<T> {
  const items = ref([]) as Ref<T[]>
  /** 只在成功时置位,失败留 false,下次进页面还能重试 */
  let loaded = false
  /** 并发调用合并成同一个请求 */
  let inflight: Promise<void> | null = null

  async function ensure(canRead: boolean): Promise<void> {
    if (loaded || !canRead) return
    if (!inflight) {
      inflight = fetchList()
        .then((list) => {
          items.value = list
          loaded = true
        })
        .catch(() => {
          // 静默吞掉:目录只影响名字显示,不该让调用它的页面整体失败
        })
        .finally(() => {
          inflight = null
        })
    }
    await inflight
  }

  /** 数据被本系统改动后调用,让下次 ensure() 重新拉取 */
  function invalidate(): void {
    loaded = false
  }

  return { ensure, invalidate, items }
}

const userDir = createDirectory<UserBriefVO>(listUserDirectory)
const deptDir = createDirectory<DepartmentBriefVO>(listDepartmentDirectory)
const roleDir = createDirectory<Role>(listRoles)

/** 用户目录:补提单人 / 处理人姓名,并给派单下拉提供候选人 */
export function useUserDirectory() {
  const userStore = useUserStore()
  // 登录即可读 —— 该接口不要求 user:manage
  const canRead: ComputedRef<boolean> = computed(() => userStore.isLogged)

  const byId = computed(() => {
    const m = new Map<number, UserBriefVO>()
    for (const u of userDir.items.value) m.set(u.userId, u)
    return m
  })

  /** id -> 姓名;查不到时给出可读的兜底而不是空白 */
  function nameOf(id: number | null | undefined): string {
    if (id === null || id === undefined) return '—'
    return byId.value.get(id)?.realName ?? `用户 #${id}`
  }

  return {
    canRead,
    ensure: () => userDir.ensure(canRead.value),
    invalidate: userDir.invalidate,
    /** 全部用户,供「选择处理人」这类下拉使用 */
    allUsers: userDir.items,
    nameOf,
  }
}

/**
 * 部门目录:补部门名,并给用户管理页的部门下拉提供选项。
 *
 * ⚠️ 部门管理页**不用**这份目录当数据源:它要编辑 remark,而目录项里没有。
 * 那一页自己调 listDepartments(),改完再 invalidate() 这份目录即可。
 */
export function useDepartmentDirectory() {
  const userStore = useUserStore()
  // 登录即可读 —— 该接口不要求 user:manage
  const canRead: ComputedRef<boolean> = computed(() => userStore.isLogged)

  const byId = computed(() => {
    const m = new Map<number, DepartmentBriefVO>()
    for (const d of deptDir.items.value) m.set(d.id, d)
    return m
  })

  /** id -> 部门名;未归属显示「全局」(与工单页的措辞一致) */
  function nameOf(id: number | null | undefined): string {
    if (id === null || id === undefined) return '全局'
    return byId.value.get(id)?.deptName ?? `部门 #${id}`
  }

  return {
    canRead,
    ensure: () => deptDir.ensure(canRead.value),
    invalidate: deptDir.invalidate,
    departments: deptDir.items,
    nameOf,
  }
}

/**
 * 角色目录:角色下拉选项(不做 id -> 名字,角色码本身就可读)。
 * 这份仍要求 user:manage —— 它只用在用户管理页的「分配角色」弹窗里,
 * 能打开那一页的人本来就有这个权限。
 */
export function useRoleDirectory() {
  const userStore = useUserStore()
  const canRead: ComputedRef<boolean> = computed(() => userStore.hasPerm('user:manage'))

  return {
    canRead,
    ensure: () => roleDir.ensure(canRead.value),
    invalidate: roleDir.invalidate,
    roles: roleDir.items,
  }
}
