/**
 * 占位数据统一出入口
 *
 * 页面一律从 '@/mock' 取数,不直接 import 具体文件。
 * 接入后端时,只要把这里换成 src/api 的导出,页面组件无需改动。
 */
export * from './user'
export * from './workorder'
