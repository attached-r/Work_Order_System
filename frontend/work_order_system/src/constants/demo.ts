/**
 * 演示账号
 *
 * 只保留「用户名 + 角色」用于登录页一键快填,不再持有密码与完整用户对象 ——
 * 接入后端后登录凭据由服务端校验,前端留一份是多余且容易与 data.sql 脱节的。
 *
 * 这些账号由后端 data.sql 初始化,口令统一为 {@link DEMO_PASSWORD}。
 */

export const DEMO_PASSWORD = 'admin123'

export interface DemoAccount {
  username: string
  /** 角色中文名,仅用于快填按钮上的副标题 */
  roleName: string
}

export const DEMO_ACCOUNTS: DemoAccount[] = [
  { username: 'admin', roleName: '管理员' },
  { username: 'submitter01', roleName: '提单人' },
  { username: 'reviewer01', roleName: '审核人' },
  { username: 'dispatcher01', roleName: '派单人' },
  { username: 'handler01', roleName: '处理人' },
]
