/**
 * 角色常量
 *
 * 角色码由后端 role.role_code 定义,基本固定不变(见后端 RoleController 的注释:
 * 「角色基本上固定的 不需要修改」),所以中文名在前端也留一份映射做展示。
 */

/** 角色标识 -> 中文名 */
export const ROLE_NAMES: Record<string, string> = {
  SUBMITTER: '提单人',
  REVIEWER: '审核人',
  DISPATCHER: '派单人',
  HANDLER: '处理人',
  ADMIN: '管理员',
}

/** 查角色中文名;未知角色码原样返回,便于暴露数据问题而不是显示成空白 */
export function getRoleName(code: string | null | undefined): string {
  if (!code) return '—'
  return ROLE_NAMES[code] ?? code
}
