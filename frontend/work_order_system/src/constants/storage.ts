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
 * Agent 对话记录 key 的**前缀**。
 *
 * 实际 key = `${AGENT_CHAT_KEY_PREFIX}${userId | 'anon'}`。
 * 不能再用一个全局 key:同一个浏览器切换账号时,新用户会直接把上一个用户的
 * 对话记录读出来(前端展示历史,与 Agent 后端的记忆是两套东西)。
 */
export const AGENT_CHAT_KEY_PREFIX = 'wo_agent_chat:'

/** Agent 当前选中会话 key 的前缀;同样按用户隔离 */
export const AGENT_ACTIVE_KEY_PREFIX = 'wo_agent_active:'

/**
 * 旧版全局 key。
 *
 * 只用于升级时清理:旧数据没有用户维度,继续保留会让「切换账号看到别人历史」
 * 的问题一直存在;清掉比错误迁移更安全。
 */
export const LEGACY_AGENT_CHAT_KEY = 'wo_agent_chat'
export const LEGACY_AGENT_ACTIVE_KEY = 'wo_agent_active'
