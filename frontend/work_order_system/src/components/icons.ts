/**
 * 图标注册表
 *
 * 侧边栏/菜单是数据驱动渲染的,图标名写在路由 meta 里(字符串),
 * 模板里没法用 unplugin 的 ElIconXxx 自动解析,所以在这里显式建一张映射表。
 * 好处是路由表保持纯数据、可序列化,新增图标只改这一处。
 */
import type { Component } from 'vue'
import {
  Odometer,
  Tickets,
  Plus,
  Setting,
  User,
  Key,
  OfficeBuilding,
  Fold,
  Expand,
  Bell,
  Search,
  Refresh,
  ArrowDown,
  SwitchButton,
  Document,
  DocumentAdd,
  Clock,
  CircleCheck,
  CircleClose,
  Warning,
  InfoFilled,
  Edit,
  View,
  Filter,
  Download,
  TrendCharts,
  Histogram,
  Star,
  Timer,
  ChatLineSquare,
  Files,
  ArrowLeft,
  Right,
} from '@element-plus/icons-vue'

export const ICONS: Record<string, Component> = {
  Odometer,
  Tickets,
  Plus,
  Setting,
  User,
  Key,
  OfficeBuilding,
  Fold,
  Expand,
  Bell,
  Search,
  Refresh,
  ArrowDown,
  SwitchButton,
  Document,
  DocumentAdd,
  Clock,
  CircleCheck,
  CircleClose,
  Warning,
  InfoFilled,
  Edit,
  View,
  Filter,
  Download,
  TrendCharts,
  Histogram,
  Star,
  Timer,
  ChatLineSquare,
  Files,
  ArrowLeft,
  Right,
}

/** 取图标组件;找不到时返回 undefined,调用方自行兜底 */
export function resolveIcon(name: string | undefined): Component | undefined {
  if (!name) return undefined
  return ICONS[name]
}
