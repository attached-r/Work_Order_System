/**
 * localStorage 键名
 *
 * 单独一个文件是为了打破循环依赖:请求层(utils/request.ts)要在 401 时清掉登录态,
 * 而登录态由 stores/user.ts 维护。两边都从这里取键名,就不必互相 import
 * (stores/user 依赖 api 依赖 utils/request,request 再反向 import store 会成环)。
 */

/** 登录令牌 */
export const TOKEN_KEY = 'wo_token'

/** 缓存的当前用户信息(含角色与权限码) */
export const USER_KEY = 'wo_user'

/**
 * Agent 对话记录。
 *
 * Agent 的会话记忆只存在它自己的进程里(每个 sessionId 最近 20 条),
 * 刷新页面或重启服务就没了,所以展示用的历史必须由前端自己落盘。
 */
export const AGENT_CHAT_KEY = 'wo_agent_chat'

/** Agent 当前选中的会话 id,与消息体分开存,切换会话时不必重写整份历史 */
export const AGENT_ACTIVE_KEY = 'wo_agent_active'
