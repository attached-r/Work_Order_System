# 智能工单系统 · 前端

Vue 3 + TypeScript + Vite 8 + Element Plus。

当前阶段完成的是**框架与页面骨架**:布局、路由、权限、表单、状态机的视觉表达都已落地,
**接口调用与数据类型是占位的**(见下方「接入后端」)。

## 技术栈

| 能力 | 选型 | 说明 |
| --- | --- | --- |
| 框架 | Vue 3.5 | `<script setup>` + Composition API |
| 构建 | Vite 8 | rolldown 内核 |
| 语言 | TypeScript 6 | `noUncheckedIndexedAccess: true`,查表一律走 `getXxx()` 函数 |
| 状态 | Pinia 4 | `user`(登录/权限)、`app`(UI 状态) |
| 路由 | Vue Router 5 | 守卫用**返回值**,不再是 `next()` |
| UI | Element Plus 2.14 | 按需引入 + SCSS 变量深度覆写 |
| 样式 | SCSS | 设计令牌集中在 `styles/tokens.scss` |

## 目录结构

```
src/
├─ styles/                  样式体系(唯一的视觉真相)
│  ├─ element/index.scss    Element Plus SCSS 变量覆写(只有 @forward)
│  ├─ tokens.scss           设计令牌:色板 / 间距 / 圆角 / 动效 / 状态色
│  ├─ base.scss             reset + 排版基线 + 工具类
│  └─ element-override.scss 组件级微调(表格/表单/菜单/弹窗…)
├─ router/index.ts          路由表 = 菜单表(一份真相)+ 登录与权限守卫
├─ stores/                  Pinia
├─ layouts/                 BasicLayout + AppSidebar + AppHeader
├─ components/              StatusTag / PriorityTag / PageHeader / icons
├─ constants/workorder.ts   状态机、码值映射、可用动作推导
├─ mock/                    占位数据(接后端后整个目录可删)
├─ types/domain.ts          领域类型(占位版)
├─ utils/datetime.ts        时间格式化/相对时间/剩余时长
└─ views/                   页面
```

## 设计语言:淡色系 · 蓝白

所有颜色都定义在 `styles/tokens.scss`,组件里**不出现裸的 `#hex`**。

- **表面** 雾蓝白底 `#f4f7fc` + 纯白卡片 + 1px 发丝线,层次靠描边而非阴影
- **主色** `#2f6fed` 一支蓝,只在选中态/主按钮/聚焦环出现,其余交给淡底衬托
- **状态色** 8 种工单状态各一组「深字 / 淡底 / 圆点」,淡雅不刺眼
- **动效** 统一缓动 `--wo-ease-out`,尊重 `prefers-reduced-motion`

细节上刻意做的事:表格行悬浮时首列一条主色竖条;侧边栏选中用左竖条 + 淡蓝药丸而非实色块;
优先级"高"加左侧竖线;登录页蓝图网格 + 光斑;内容区极淡点阵网格。
这些是「简约而不简单」的着力点。

**字体**:拉丁字母与数字用自托管的 Plus Jakarta Sans(离线可用),中文回落系统黑体,
数字全局开 `tabular-nums` 保证编号/时间对齐。

## 权限模型

前端是后端 `@RequiresPermission` 的镜像,只决定**按钮与菜单显不显示**,不承担安全职责。

- 路由 `meta.perm` → 守卫拦截直达 + 菜单自动过滤
- 组件内用 `userStore.hasPerm('workorder:review')`
- 工单动作的显隐由 `availableActions(status, perms)` 统一推导,与后端状态机 `TRANSITIONS` 对齐

## 接入后端

接口和类型是刻意留空的,替换路径已经收敛好:

1. **建 `src/api/`** —— 封装 axios/fetch 与拦截器,按域拆分文件
2. **换类型** —— `src/types/domain.ts` 换成按 OpenAPI 生成的定义,字段名不用改
3. **换数据源** —— 页面里所有 `@/mock` 的 import 换成 `@/api`。搜索点已用 `TODO(api):` 标注,
   每处都写明了对应的后端接口,例如:

   ```ts
   // TODO(api): 换成 GET /workorder/page
   const res = mockWorkOrderPage({ ... })
   ```

4. **删掉 `src/mock/`**
5. **打开代理** —— `vite.config.ts` 里的 `server.proxy` 已备好注释,取消注释即可

页面组件只依赖 `@/types/domain` 的类型名与 `@/mock` 的导出名,所以替换过程对页面透明。

## 已知的坑(改代码前先看)

- `vite.config.ts` 的 `additionalData` 用了**函数形式**并跳过主题文件,否则会自引用循环加载
- `element/index.scss` 里 `$font-family` 的 key 是空字符串 `''`,不是 `'base'`
- 生成物 `src/types/auto-imports.d.ts`、`components.d.ts` 必须提交,且必须在 `src/` 下
  (根目录不在 `tsconfig.app.json` 的 `include` 里,会导致 `vue-tsc` 找不到 `ref`)
- `el-table` 的插槽把 `row` 声明成 `DefaultRow`(`Record<PropertyKey, any>`),
  传给具体类型函数时需要在调用点 `as XxxVO`
- 裸图标名(如 `<ArrowDown />`)不会被 unplugin 的 resolver 解析,必须显式 import

## 常用命令

```sh
npm install      # 安装依赖
npm run dev      # 开发服务器
npm run build    # 类型检查 + 构建
npm run type-check  # 只跑 vue-tsc
```

## 演示账号

口令均为 `admin123`(与后端 `data.sql` 一致),登录页可点击快填:

| 账号 | 角色 | 能做什么 |
| --- | --- | --- |
| `admin` | 管理员 | 全部权限,含用户/角色/部门管理 |
| `submitter01` | 提单人 | 提交、修改、撤回、验收 |
| `reviewer01` | 审核人 | 审核本部门提单 |
| `dispatcher01` | 派单人 | 派单 |
| `handler01` | 处理人 | 处理与转派 |
