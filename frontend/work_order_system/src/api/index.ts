/**
 * 接口统一出入口
 *
 * 页面一律从 '@/api' 取数,不直接 import 具体模块 —— 与原先的 '@/mock' 保持同一约定,
 * 各请求模块的文件名也与后端 Controller 一一对应(user / role / department / permission / workorder)。
 */
export * from './auth'
export * from './user'
export * from './role'
export * from './department'
export * from './permission'
export * from './workorder'
