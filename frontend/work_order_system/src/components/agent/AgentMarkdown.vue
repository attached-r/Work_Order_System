<script setup lang="ts">
/**
 * Agent 回答的 Markdown 渲染
 *
 * 这里只负责「把字符串变成安全的 HTML + 代码块复制」,
 * 排版细节统一在下方 :deep() 里收口,避免每个消息组件各写一套。
 */
import { computed } from 'vue'
import { renderMarkdown } from '@/utils/markdown'
import { copyText } from '@/utils/clipboard'
import type { WorkOrderRef } from '@/utils/workOrderRef'

const props = withDefaults(
  defineProps<{
    content: string
    /** 正在流式输出:在最后一段末尾显示闪烁光标 */
    streaming?: boolean
    /** 是否把回答里的工单引用渲染成可点击链接(由页面按权限传入) */
    enableWorkOrderLinks?: boolean
  }>(),
  { streaming: false, enableWorkOrderLinks: false },
)

const emit = defineEmits<{
  /** 点击工单引用;解析 id / 导航由页面负责,Markdown 层不碰路由和接口 */
  'open-workorder': [ref: WorkOrderRef]
}>()

const html = computed(() =>
  renderMarkdown(props.content, { workOrderLinks: props.enableWorkOrderLinks }),
)

/**
 * 统一的事件委托。
 *
 * 代码块和工单链接都是 v-html 生成的,DOM 节点上绑不了 Vue 事件,
 * 所以在外层容器监听点击,再向上找目标。
 * 先处理代码复制,再处理工单跳转,两者互不干扰。
 */
async function handleClick(event: MouseEvent) {
  const target = event.target
  if (!(target instanceof Element)) return

  // 步骤 1:代码块复制
  const button = target.closest<HTMLButtonElement>('[data-copy-code]')
  if (button) {
    const code = button.closest('.md-code')?.querySelector('code')?.textContent ?? ''
    if (!code) return

    const ok = await copyText(code)
    const original = button.textContent ?? '复制'
    button.textContent = ok ? '已复制' : '复制失败'
    window.setTimeout(() => {
      button.textContent = original
    }, 1600)
    return
  }

  // 步骤 2:工单引用
  const link = target.closest<HTMLAnchorElement>('a.md-workorder')
  if (!link) return

  // 中键 / 带修饰键时保留浏览器原生行为(新标签页打开 href)
  if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
    return
  }

  event.preventDefault()

  const idAttr = link.dataset.workOrderId
  const orderNo = link.dataset.workOrderNo

  if (idAttr) {
    const id = Number(idAttr)
    if (Number.isSafeInteger(id) && id > 0) emit('open-workorder', { id })
    return
  }
  if (orderNo) emit('open-workorder', { orderNo })
}
</script>

<template>
  <div class="md" @click="handleClick">
    <div class="md__body" :class="{ 'is-streaming': streaming }" v-html="html"></div>
  </div>
</template>

<style scoped lang="scss">
.md {
  font-size: 14.5px;
  line-height: 1.75;
  color: var(--wo-ink-1);
  // 长英文单词 / 哈希值也要能断行,否则会把消息区撑出横向滚动条
  overflow-wrap: anywhere;
}

.md__body {
  // 首尾元素不要额外留白,气泡的间距由消息组件统一控制
  > :first-child {
    margin-top: 0;
  }

  > :last-child {
    margin-bottom: 0;
  }

  :deep(p),
  :deep(ul),
  :deep(ol),
  :deep(blockquote),
  :deep(pre),
  :deep(table),
  :deep(hr) {
    margin: 10px 0;
  }

  :deep(h1),
  :deep(h2),
  :deep(h3),
  :deep(h4),
  :deep(h5),
  :deep(h6) {
    margin: 20px 0 8px;
    line-height: 1.35;
  }

  :deep(h1) {
    font-size: 20px;
  }

  :deep(h2) {
    font-size: 18px;
  }

  :deep(h3) {
    font-size: 16px;
  }

  :deep(h4),
  :deep(h5),
  :deep(h6) {
    font-size: 14.5px;
  }

  :deep(ul),
  :deep(ol) {
    padding-left: 22px;
  }

  :deep(li + li) {
    margin-top: 4px;
  }

  // 引用块:淡蓝底 + 左侧主色细线,和项目里「提示条」的风格一致
  :deep(blockquote) {
    padding: 6px 14px;
    border-left: 3px solid var(--wo-hairline-brand);
    border-radius: 0 var(--wo-radius-sm) var(--wo-radius-sm) 0;
    background: var(--wo-brand-wash);
    color: var(--wo-ink-2);
  }

  // 行内代码
  :deep(code) {
    padding: 1px 5px;
    border: 1px solid var(--wo-hairline);
    border-radius: 4px;
    background: var(--wo-surface-sunken);
    color: var(--wo-ink-1);
    font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', Menlo, monospace;
    font-size: 0.9em;
  }

  // ---- 代码块 ----
  :deep(.md-code) {
    margin: 12px 0;
    border: 1px solid var(--wo-hairline);
    border-radius: var(--wo-radius);
    overflow: hidden;
    background: var(--wo-surface-sunken);
  }

  :deep(.md-code__bar) {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 12px;
    padding: 5px 10px;
    border-bottom: 1px solid var(--wo-hairline);
    background: var(--wo-surface-raised);
  }

  :deep(.md-code__lang) {
    font-size: 11px;
    font-weight: 600;
    letter-spacing: 0.08em;
    text-transform: uppercase;
    color: var(--wo-ink-3);
  }

  :deep(.md-code__copy) {
    padding: 2px 7px;
    border: none;
    border-radius: 4px;
    background: transparent;
    color: var(--wo-ink-3);
    font-family: inherit;
    font-size: 12px;
    cursor: pointer;
    transition:
      color var(--wo-dur-fast) var(--wo-ease),
      background-color var(--wo-dur-fast) var(--wo-ease);

    &:hover {
      color: var(--wo-brand);
      background: var(--wo-brand-wash);
    }
  }

  :deep(.md-code__pre) {
    margin: 0;
    padding: 12px 14px;
    overflow-x: auto;
    background: var(--wo-surface-sunken);
  }

  :deep(.md-code__pre code) {
    padding: 0;
    border: none;
    background: none;
    font-size: 12.5px;
    line-height: 1.65;
  }

  // ---- 表格:允许横向滚动,列多时不挤压 ----
  :deep(table) {
    display: block;
    width: max-content;
    max-width: 100%;
    overflow-x: auto;
    border-collapse: collapse;
    font-size: 13.5px;
  }

  :deep(th),
  :deep(td) {
    padding: 7px 11px;
    border: 1px solid var(--wo-hairline);
    text-align: left;
    vertical-align: top;
  }

  :deep(th) {
    background: var(--wo-surface-sunken);
    color: var(--wo-ink-1);
    font-weight: 600;
  }

  :deep(a) {
    color: var(--wo-brand);
    text-decoration: underline;
    text-underline-offset: 2px;
  }

  // 工单引用:虚线强调「这是可跳转的实体」,和普通外链区分开
  :deep(.md-workorder) {
    padding: 0 2px;
    border-radius: 3px;
    color: var(--wo-brand);
    font-weight: 600;
    text-decoration: underline dashed;
    text-decoration-thickness: 1px;
    text-underline-offset: 3px;
    cursor: pointer;
    transition: background-color var(--wo-dur-fast) var(--wo-ease);

    &:hover {
      background: var(--wo-brand-wash);
      text-decoration-style: solid;
    }

    &:focus-visible {
      outline: 2px solid var(--wo-hairline-brand);
      outline-offset: 1px;
    }
  }

  :deep(img) {
    max-width: 100%;
    border-radius: var(--wo-radius-sm);
  }

  :deep(hr) {
    border: none;
    border-top: 1px solid var(--wo-hairline);
  }
}

</style>

<!--
  光标规则刻意不放在 scoped 里:
  v-html 生成的节点拿不到组件的 data-v 属性,`:last-child` 加了作用域后就匹配不上。
  `.md__body.is-streaming` 这个组合足够独特,不会污染其它页面。
-->
<style lang="scss">
.md__body.is-streaming > :last-child::after {
  content: '';
  display: inline-block;
  width: 2px;
  height: 1.05em;
  margin-left: 2px;
  vertical-align: -0.15em;
  background: var(--wo-brand);
  animation: md-cursor-blink 1s steps(2, start) infinite;
}

@keyframes md-cursor-blink {
  to {
    visibility: hidden;
  }
}
</style>
