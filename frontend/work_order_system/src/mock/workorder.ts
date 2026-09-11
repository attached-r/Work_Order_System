/**
 * 工单占位数据
 *
 * 覆盖全部 8 种状态,以及超时/未超时、有无处理人、有无资源明细等分支,
 * 目的是让列表、详情、时间轴、状态标签的每种视觉分支都能被看到。
 *
 * 时间用「相对当前时间偏移」生成,所以页面上的"3 小时前""已超时 2 天"永远是对的。
 */
import type { PageResult, WorkOrderDetailVO, WorkOrderVO, WorkOrderResource, WorkOrderOperateLog } from '@/types/domain'
import { hoursAgo } from '@/utils/datetime'

/** 简单确定性伪随机,保证每次刷新数据一致,便于比对 UI */
function seeded(seed: number): () => number {
  let s = seed
  return () => {
    s = (s * 1103515245 + 12345) % 2147483648
    return s / 2147483648
  }
}

const TITLES = [
  '生产线 MES 系统扫码枪无法识别工单条码',
  '申请开通测试环境数据库只读账号',
  '客户管理模块需要增加批量导出功能',
  '办公区三楼打印机频繁卡纸',
  '申请扩容预发布环境服务器内存至 32G',
  '报表中心月度统计口径需要调整',
  'VPN 客户端在 Windows 11 上连接失败',
  '申请新增一名外包人员的系统账号',
  '订单列表页加载超过 8 秒需要优化',
  '会议室投影仪 HDMI 接口松动',
  '申请将日志保留周期从 7 天延长至 30 天',
  '移动端 H5 页面在 iOS Safari 上样式错位',
  '财务系统对账单导出后金额列错位',
  '申请为运维团队开通监控告警查看权限',
  '需求：工单系统增加超时自动提醒',
  '仓库扫描终端电池续航异常下降',
  '申请测试环境域名增加 HTTPS 证书',
  '考勤机数据同步延迟超过 2 小时',
  '申请调整生产环境发布窗口至凌晨',
  '用户反馈登录页验证码看不清',
  '数据库慢查询告警频繁触发',
  '申请新增二级部门「数据平台组」',
  '接口文档页面无法正常加载',
  '申请导出近半年工单统计报表',
  '内网 DNS 解析偶发失败',
  '需求：支持工单批量指派处理人',
]

const RESOURCE_POOL: Array<Omit<WorkOrderResource, 'id' | 'workOrderId'>> = [
  { resourceName: '测试环境 MySQL 实例', resourceType: '数据库', quantity: 1, remark: '只读权限即可' },
  { resourceName: '云服务器 4C8G', resourceType: '计算资源', quantity: 2, remark: '预发布环境使用' },
  { resourceName: '对象存储 100GB', resourceType: '存储', quantity: 1, remark: null },
  { resourceName: '堡垒机账号', resourceType: '账号', quantity: 1, remark: '有效期 3 个月' },
  { resourceName: 'HTTPS 证书', resourceType: '证书', quantity: 1, remark: '泛域名证书' },
]

/** 状态分布:让列表首页能同时看到多种状态 */
const STATUS_CYCLE = [0, 1, 2, 3, 4, 2, 3, 5, 6, 7, 2, 4, 0, 1, 4, 3, 2, 4, 5, 6, 2, 4, 3, 1, 4, 0]

/** 提单人:2 小王(研发) / 6 小周(运维) */
const SUBMITTERS = [
  { id: 2, dept: 1 },
  { id: 6, dept: 2 },
]

/** 处理人 */
const HANDLERS = [5, 7]

const STATUS_DESC: Record<number, string> = {
  0: '待审核',
  1: '待派单',
  2: '处理中',
  3: '待验收',
  4: '已完成',
  5: '已驳回',
  6: '已取消',
  7: '已超时',
}

/** 处理人只有在进入「处理中」之后才存在 */
function handlerFor(status: number, idx: number): number | null {
  if (status === 0 || status === 1 || status === 5 || status === 6) return null
  return HANDLERS[idx % HANDLERS.length] ?? null
}

function buildList(): WorkOrderVO[] {
  const rand = seeded(20260911)
  return TITLES.map((title, i) => {
    const status = STATUS_CYCLE[i] ?? 0
    const submitter = SUBMITTERS[i % SUBMITTERS.length] ?? SUBMITTERS[0]!
    // 越靠前的工单越新,方便观察"最新在上"的排序观感
    const createdHours = 2 + i * 7 + Math.floor(rand() * 5)
    // 已超时的单,到期时间必然在过去;其余给 3~5 天
    const expireHours = status === 7 ? createdHours - 30 : -(72 + Math.floor(rand() * 48))

    return {
      id: i + 1,
      orderNo: `WO${String(20260911000 + i + 1)}`,
      userId: submitter.id,
      departmentId: submitter.dept,
      handlerId: handlerFor(status, i),
      title,
      orderType: ((i % 3) + 1) as number,
      priority: ((i % 3) + 1) as number,
      status,
      statusDesc: STATUS_DESC[status] ?? '未知',
      createTime: hoursAgo(createdHours),
      updateTime: hoursAgo(Math.max(0, createdHours - 3)),
      expireTime: hoursAgo(expireHours),
    }
  })
}

export const MOCK_WORK_ORDERS: WorkOrderVO[] = buildList()

/** 生成一条工单的操作日志链,按状态反推它经历过哪些事件 */
function buildLogs(order: WorkOrderVO): WorkOrderOperateLog[] {
  const logs: WorkOrderOperateLog[] = []
  const base = order.id * 10
  let seq = 0

  const push = (operateType: number, from: number | null, to: number, operatorId: number, remark: string | null) => {
    seq += 1
    logs.push({
      id: base + seq,
      workOrderId: order.id,
      operatorId,
      operateType,
      fromStatus: from,
      toStatus: to,
      remark,
      createTime: hoursAgo(Math.max(0, 40 - order.id * 0.5 - seq * 3)),
    })
  }

  push(1, null, 0, order.userId, '提交工单')

  const s = order.status
  if (s === 0) return logs

  if (s === 5) {
    push(3, 0, 5, 3, '描述信息不完整,请补充故障发生时间和具体报错内容')
    return logs
  }

  if (s === 6) {
    push(9, 0, 6, order.userId, '问题已自行解决,先撤回')
    return logs
  }

  push(2, 0, 1, 3, '信息完整,审核通过')

  if (s === 1) return logs

  push(4, 1, 2, 4, `指派给 ${order.handlerId === 7 ? '处理人小吴' : '处理人小李'}`)

  if (s === 2) return logs

  if (s === 7) {
    push(10, 2, 7, 1, '超过约定处理时限,系统自动关闭')
    return logs
  }

  push(5, 2, 3, order.handlerId ?? 5, '已定位并修复,请提单人验证')

  if (s === 3) return logs

  push(6, 3, 4, order.userId, '验证通过,问题已解决')

  return logs
}

/** 按 id 取完整详情 */
export function mockWorkOrderDetail(id: number): WorkOrderDetailVO | null {
  const listItem = MOCK_WORK_ORDERS.find((o) => o.id === id)
  if (!listItem) return null

  const rand = seeded(id * 977)
  const resourceCount = id % 4 === 0 ? 2 : id % 3 === 0 ? 1 : 0
  const resources: WorkOrderResource[] = []
  for (let i = 0; i < resourceCount; i++) {
    const tpl = RESOURCE_POOL[(id + i) % RESOURCE_POOL.length]
    if (tpl) resources.push({ ...tpl, id: id * 100 + i, workOrderId: id })
  }

  return {
    ...listItem,
    content:
      `${listItem.title}。\n\n现象描述:该问题自昨日下午开始出现,影响范围涉及${listItem.departmentId === 2 ? '运维部' : '研发部'}日常操作,` +
      `目前已造成一定影响。\n\n已尝试的排查:重启相关服务、清理本地缓存,均未彻底解决。` +
      `期望处理时限:${listItem.priority === 1 ? '尽快,影响业务' : '本周内'}。\n\n` +
      `补充说明(随机数便于区分不同工单):${Math.floor(rand() * 100000)}`,
    remark:
      listItem.status === 5
        ? '描述信息不完整,请补充故障发生时间和具体报错内容'
        : listItem.status === 6
          ? '问题已自行解决,先撤回'
          : listItem.status === 7
            ? '超过约定处理时限,系统自动关闭'
            : null,
    resources,
    logs: buildLogs(listItem),
  }
}

/**
 * 分页查询(纯内存过滤 + 切片),签名与后端 GET /workorder/page 对齐。
 * 接入后端后本函数直接删掉,换成 api 调用即可。
 */
export function mockWorkOrderPage(params: {
  current?: number
  size?: number
  status?: number | null
  orderType?: number | null
  keyword?: string
}): PageResult<WorkOrderVO> {
  const current = params.current ?? 1
  const size = params.size ?? 10
  const keyword = (params.keyword ?? '').trim().toLowerCase()

  const filtered = MOCK_WORK_ORDERS.filter((o) => {
    if (params.status !== null && params.status !== undefined && o.status !== params.status) return false
    if (params.orderType !== null && params.orderType !== undefined && o.orderType !== params.orderType) return false
    if (keyword) {
      const hit =
        o.title.toLowerCase().includes(keyword) || o.orderNo.toLowerCase().includes(keyword)
      if (!hit) return false
    }
    return true
  })

  const start = (current - 1) * size
  return {
    records: filtered.slice(start, start + size),
    total: filtered.length,
    current,
    size,
  }
}

/** 各状态数量,给工作台的分布图用 */
export function mockStatusDistribution(): Array<{ status: number; label: string; count: number }> {
  return [0, 1, 2, 3, 4, 5, 6, 7].map((status) => ({
    status,
    label: STATUS_DESC[status] ?? '未知',
    count: MOCK_WORK_ORDERS.filter((o) => o.status === status).length,
  }))
}
