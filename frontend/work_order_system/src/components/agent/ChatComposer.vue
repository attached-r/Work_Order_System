<script setup lang="ts">
/**
 * 聊天输入区
 *
 * 用原生 textarea 而不是 el-input type="textarea":
 *   - 自动增高需要精确控制 style.height;
 *   - Element Plus 的 textarea 外层还有一层 wrapper,和「圆角输入框 + 底部工具行」
 *     这种聊天类布局不匹配,覆写成本反而更高。
 */
import { nextTick, ref, watch } from 'vue'
import { Promotion, VideoPause } from '@element-plus/icons-vue'

/** 双向绑定草稿;发送成功后由本组件清空 */
const draft = defineModel<string>({ default: '' })

const props = withDefaults(
  defineProps<{
    /** 正在生成:右侧按钮切换为「停止」 */
    streaming?: boolean
    /** 整体禁用(例如未登录) */
    disabled?: boolean
    maxLength?: number
    placeholder?: string
    /** 输入框下方的提示文案；M4 有写权限时换成「写操作需确认」的说明 */
    note?: string
  }>(),
  {
    streaming: false,
    disabled: false,
    maxLength: 2000,
    placeholder: '向工单 Agent 提问,例如:审核驳回后怎么重新提交?',
    note: '回答由 AI 生成,请结合工单实际情况核对。',
  },
)

const emit = defineEmits<{
  send: [text: string]
  stop: []
}>()

const textareaRef = ref<HTMLTextAreaElement | null>(null)

/** 中文输入法拼写过程中按 Enter 是「上屏」,不能当发送 */
const composing = ref(false)

/** 输入框最高约 6 行,超过后内部滚动 */
const MAX_HEIGHT = 160

/** 根据内容算高度;先归零,否则删除文字时高度不会缩回去 */
function resize(): void {
  const el = textareaRef.value
  if (!el) return

  el.style.height = 'auto'
  el.style.height = `${Math.min(el.scrollHeight, MAX_HEIGHT)}px`
  el.style.overflowY = el.scrollHeight > MAX_HEIGHT ? 'auto' : 'hidden'
}

watch(
  draft,
  () => {
    void nextTick(resize)
  },
  { flush: 'post' },
)

function handleKeydown(event: KeyboardEvent): void {
  if (event.key !== 'Enter' || event.shiftKey) return
  // 三个条件缺一不可:isComposing 覆盖多数现代浏览器,keyCode 229 兼容旧实现
  if (event.isComposing || composing.value || event.keyCode === 229) return

  event.preventDefault()
  submit()
}

function submit(): void {
  if (props.disabled || props.streaming) return

  const text = draft.value.trim()
  if (!text) return

  emit('send', text)
  draft.value = ''
  void nextTick(() => {
    resize()
    focus()
  })
}

function focus(): void {
  textareaRef.value?.focus()
}

defineExpose({ focus })
</script>

<template>
  <div class="composer" :class="{ 'is-disabled': disabled }">
    <div class="composer__box">
      <textarea
        ref="textareaRef"
        v-model="draft"
        class="composer__input"
        rows="1"
        :maxlength="maxLength"
        :placeholder="placeholder"
        :disabled="disabled"
        @input="resize"
        @keydown="handleKeydown"
        @compositionstart="composing = true"
        @compositionend="composing = false"
      ></textarea>

      <div class="composer__foot">
        <span class="composer__hint">Enter 发送 · Shift + Enter 换行</span>

        <div class="composer__actions">
          <!-- 字数只在输入后出现,平时不占视觉 -->
          <span
            v-if="draft.length"
            class="composer__count wo-num"
            :class="{ 'is-near': draft.length > maxLength * 0.9 }"
          >
            {{ draft.length }} / {{ maxLength }}
          </span>

          <button
            v-if="streaming"
            type="button"
            class="composer__btn composer__btn--stop"
            title="停止生成"
            @click="emit('stop')"
          >
            <el-icon :size="16"><VideoPause /></el-icon>
          </button>

          <button
            v-else
            type="button"
            class="composer__btn"
            :disabled="disabled || !draft.trim()"
            title="发送"
            @click="submit"
          >
            <el-icon :size="16"><Promotion /></el-icon>
          </button>
        </div>
      </div>
    </div>

    <p class="composer__note">{{ note }}</p>
  </div>
</template>

<style scoped lang="scss">
.composer {
  width: 100%;
}

.composer__box {
  padding: 10px 12px 8px;
  border: 1px solid var(--wo-hairline-strong);
  border-radius: var(--wo-radius-lg);
  background: var(--wo-surface);
  box-shadow: var(--wo-shadow-sm);
  transition:
    border-color var(--wo-dur-fast) var(--wo-ease),
    box-shadow var(--wo-dur-fast) var(--wo-ease);

  // 聚焦光晕与项目里其他输入控件保持同一套语言
  &:focus-within {
    border-color: var(--wo-hairline-brand);
    box-shadow:
      0 0 0 3px var(--wo-brand-ring),
      var(--wo-shadow-sm);
  }
}

.composer__input {
  display: block;
  width: 100%;
  max-height: 160px;
  padding: 2px 4px 6px;
  border: none;
  outline: none;
  resize: none;
  background: transparent;
  color: var(--wo-ink-1);
  font-family: inherit;
  font-size: 14.5px;
  line-height: 1.65;
  overflow-y: hidden;

  &::placeholder {
    color: var(--wo-ink-4);
  }

  &:disabled {
    cursor: not-allowed;
  }
}

.composer__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 30px;
  padding-top: 4px;
  border-top: 1px solid var(--wo-hairline);
}

.composer__hint {
  padding-left: 4px;
  font-size: 11px;
  color: var(--wo-ink-4);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.composer__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
}

.composer__count {
  font-size: 11px;
  color: var(--wo-ink-4);

  &.is-near {
    color: var(--wo-pr-2-fg);
  }
}

.composer__btn {
  width: 34px;
  height: 34px;
  display: grid;
  place-items: center;
  border: none;
  border-radius: 50%;
  background: var(--wo-brand);
  color: #fff;
  cursor: pointer;
  box-shadow: 0 2px 6px rgba(47, 111, 237, 0.28);
  transition:
    background-color var(--wo-dur-fast) var(--wo-ease),
    transform var(--wo-dur-fast) var(--wo-ease),
    opacity var(--wo-dur-fast) var(--wo-ease);

  &:hover:not(:disabled) {
    background: var(--wo-brand-hover);
    transform: translateY(-1px);
  }

  &:active:not(:disabled) {
    transform: translateY(0);
  }

  &:disabled {
    background: var(--wo-surface-sunken);
    color: var(--wo-ink-4);
    box-shadow: none;
    cursor: not-allowed;
  }
}

.composer__btn--stop {
  background: var(--wo-surface-sunken);
  color: var(--wo-st-5-fg);
  box-shadow: inset 0 0 0 1px var(--wo-hairline-strong);

  &:hover:not(:disabled) {
    background: var(--wo-st-5-bg);
    color: var(--wo-st-5-fg);
  }
}

.composer__note {
  margin-top: 7px;
  text-align: center;
  font-size: 11px;
  color: var(--wo-ink-4);
}

.composer.is-disabled .composer__box {
  background: var(--wo-surface-raised);
}
</style>
