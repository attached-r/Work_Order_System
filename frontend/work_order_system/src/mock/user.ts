/**
 * 用户 / 角色 / 部门 / 权限 的占位数据
 *
 * 数据取自后端 SQL/data.sql 的初始化脚本,保证页面观感与真实数据一致。
 * 后续接入后端时,整个 src/mock 目录可直接删除,调用方换成 src/api。
 */
import type { Department, PermissionVO, Role, UserVO } from '@/types/domain'

/** 角色标识 -> 中文名 */
export const ROLE_NAMES: Record<string, string> = {
  SUBMITTER: '提单人',
  REVIEWER: '审核人',
  DISPATCHER: '派单人',
  HANDLER: '处理人',
  ADMIN: '管理员',
}

export function getRoleName(code: string): string {
  return ROLE_NAMES[code] ?? code
}

/** 角色 -> 权限码,与 data.sql 的 role_permission 一致 */
const ROLE_PERMS: Record<string, string[]> = {
  ADMIN: [
    'workorder:create',
    'workorder:modify',
    'workorder:withdraw',
    'workorder:accept',
    'workorder:query',
    'workorder:review',
    'workorder:dispatch',
    'workorder:process',
    'workorder:transfer',
    'user:manage',
  ],
  SUBMITTER: [
    'workorder:create',
    'workorder:modify',
    'workorder:withdraw',
    'workorder:accept',
    'workorder:query',
  ],
  REVIEWER: ['workorder:review', 'workorder:query'],
  DISPATCHER: ['workorder:dispatch', 'workorder:query'],
  HANDLER: ['workorder:process', 'workorder:transfer', 'workorder:query'],
}

/** 展开角色列表得到权限码集合 */
export function permsOfRoles(roles: string[]): string[] {
  const set = new Set<string>()
  for (const r of roles) {
    for (const p of ROLE_PERMS[r] ?? []) set.add(p)
  }
  return [...set]
}

export interface MockAccount {
  username: string
  password: string
  user: UserVO
}

/** 演示账号,口令均为 admin123(与 data.sql 一致) */
export const MOCK_ACCOUNTS: MockAccount[] = [
  {
    username: 'admin',
    password: 'admin123',
    user: {
      userId: 1,
      username: 'admin',
      realName: '超级管理员',
      departmentId: null,
      departmentName: null,
      status: 1,
      roles: ['ADMIN'],
      perms: permsOfRoles(['ADMIN']),
    },
  },
  {
    username: 'submitter01',
    password: 'admin123',
    user: {
      userId: 2,
      username: 'submitter01',
      realName: '提单人小王',
      departmentId: 1,
      departmentName: '研发部',
      status: 1,
      roles: ['SUBMITTER'],
      perms: permsOfRoles(['SUBMITTER']),
    },
  },
  {
    username: 'reviewer01',
    password: 'admin123',
    user: {
      userId: 3,
      username: 'reviewer01',
      realName: '审核人小赵',
      departmentId: 1,
      departmentName: '研发部',
      status: 1,
      roles: ['REVIEWER'],
      perms: permsOfRoles(['REVIEWER']),
    },
  },
  {
    username: 'dispatcher01',
    password: 'admin123',
    user: {
      userId: 4,
      username: 'dispatcher01',
      realName: '派单人小钱',
      departmentId: 1,
      departmentName: '研发部',
      status: 1,
      roles: ['DISPATCHER'],
      perms: permsOfRoles(['DISPATCHER']),
    },
  },
  {
    username: 'handler01',
    password: 'admin123',
    user: {
      userId: 5,
      username: 'handler01',
      realName: '处理人小李',
      departmentId: 1,
      departmentName: '研发部',
      status: 1,
      roles: ['HANDLER'],
      perms: permsOfRoles(['HANDLER']),
    },
  },
]

export const MOCK_DEPARTMENTS: Department[] = [
  { id: 1, deptCode: 'RND', deptName: '研发部', remark: '产品研发与技术支持' },
  { id: 2, deptCode: 'OPS', deptName: '运维部', remark: '基础设施与线上运维' },
]

export const MOCK_ROLES: Role[] = [
  { id: 1, roleCode: 'SUBMITTER', roleName: '提单人', remark: '创建/提交/撤回/取消工单,并对处理结果验收' },
  { id: 2, roleCode: 'REVIEWER', roleName: '审核人', remark: '审核本部门提单' },
  { id: 3, roleCode: 'DISPATCHER', roleName: '派单人', remark: '查看本部门待派单工单并分配处理人' },
  { id: 4, roleCode: 'HANDLER', roleName: '处理人', remark: '处理分配给自己的工单,支持完成与转派' },
  { id: 5, roleCode: 'ADMIN', roleName: '管理员', remark: '管理用户/角色/部门,并查看全部工单' },
]

/** 用户分页数据,比种子数据多一些以便观察分页效果 */
export const MOCK_USERS: UserVO[] = [
  ...MOCK_ACCOUNTS.map((a) => a.user),
  {
    userId: 6,
    username: 'submitter02',
    realName: '提单人小周',
    departmentId: 2,
    departmentName: '运维部',
    status: 1,
    roles: ['SUBMITTER'],
    perms: permsOfRoles(['SUBMITTER']),
  },
  {
    userId: 7,
    username: 'handler02',
    realName: '处理人小吴',
    departmentId: 2,
    departmentName: '运维部',
    status: 1,
    roles: ['HANDLER'],
    perms: permsOfRoles(['HANDLER']),
  },
  {
    userId: 8,
    username: 'reviewer02',
    realName: '审核人小郑',
    departmentId: 2,
    departmentName: '运维部',
    status: 0,
    roles: ['REVIEWER'],
    perms: permsOfRoles(['REVIEWER']),
  },
]

/** 权限树,对应 GET /permission/tree 的返回结构 */
export const MOCK_PERMISSION_TREE: PermissionVO[] = [
  {
    id: 100,
    permCode: 'workorder',
    permName: '工单管理',
    children: [
      { id: 1, permCode: 'workorder:create', permName: '提交工单', children: [] },
      { id: 2, permCode: 'workorder:modify', permName: '修改工单', children: [] },
      { id: 3, permCode: 'workorder:withdraw', permName: '撤回/取消工单', children: [] },
      { id: 5, permCode: 'workorder:query', permName: '查询工单', children: [] },
      { id: 6, permCode: 'workorder:review', permName: '审核工单', children: [] },
      { id: 7, permCode: 'workorder:dispatch', permName: '派单工单', children: [] },
      { id: 8, permCode: 'workorder:process', permName: '处理工单', children: [] },
      { id: 9, permCode: 'workorder:transfer', permName: '转派工单', children: [] },
      { id: 4, permCode: 'workorder:accept', permName: '验收工单', children: [] },
    ],
  },
  {
    id: 200,
    permCode: 'system',
    permName: '系统管理',
    children: [{ id: 10, permCode: 'user:manage', permName: '用户/角色/部门管理', children: [] }],
  },
]

/** 角色 -> 已勾选的权限 id,用于角色权限分配页回显 */
export const MOCK_ROLE_PERM_IDS: Record<number, number[]> = {
  1: [1, 2, 3, 4, 5],
  2: [6, 5],
  3: [7, 5],
  4: [8, 9, 5],
  5: [1, 2, 3, 4, 5, 6, 7, 8, 9, 10],
}
