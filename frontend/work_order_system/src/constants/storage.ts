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
