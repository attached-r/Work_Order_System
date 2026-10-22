<script setup lang="ts">
/**
 * 写操作确认条（M4）
 *
 * 助手返回预览后，在对话内给用户一个明确的「确认执行」入口。
 *
 * 边界：
 *   - 按钮只发送 `WRITE_CONFIRM_KEYWORD`（默认「确认执行」）这一条普通消息，
 *     不接触 confirmationToken，也不直接调用项目一写接口；
 *   - 前端不做「能不能写」的判断，真正的权限、数据范围、状态机与并发校验
 *     仍由 agent 与项目一裁决；
 *   - 不使用弹窗：M4 的确认语义仍是对话内确认，弹窗会让人误以为前端持有写权限。
 */
import { computed } from 'vue'
import { Select, Warning } from '@element-plus/icons-vue'
import { WRITE_CONFIRM_KEYWORD } from '@/constants/agent'
import { getStatusLabel } from '@/constants/workorder'
import { getWriteActionLabel } from '@/utils/writePreview'
import type { WritePreviewInfo } from '@/utils/writePreview'

const props = withDefaults(
  defineProps<{
    /** 识别结果：structured=true 时能展示工单与状态细节 */
    info: WritePreviewInfo
    /** 任意消息正在流式输出时禁用按钮，避免重复提交 */
    busy?: boolean
    /** 当前用户是否有工单查询权限；没有就不显示「查看工单」 */
    canOpenOrder?: boolean
  }>(),
  {
    busy: false,
    canOpenOrder: false,
  },
)

const emit = defineEmits<{
  confirm: []
  'open-order': []
}>()

const preview = computed(() => props.info.data)
const structured = computed(() => props.info.structured)

const actionLabel = computed(() => getWriteActionLabel(preview.value.action))

const title = computed(() =>
  structured.value && preview.value.action
    ? `待确认：${actionLabel.value}`
    : '助手正在等待你确认一次工单操作',
)

/** 当前状态：优先后端给的中文名，缺失时用本地状态表兜底 */
const fromStatusText = computed(() =>
  preview.value.statusDesc ?? getStatusLabel(preview.value.status),
)

/** 目标状态：缺失时整行不展示，避免出现「→ 未知」 */
const toStatusText = computed(() => {
  if (preview.value.targetStatusDesc) return preview.value.targetStatusDesc
  if (preview.value.targetStatus !== undefined) return getStatusLabel(preview.value.targetStatus)
  return ''
})

const hasFlow = computed(
  () => preview.value.status !== undefined && preview.value.targetStatus !== undefined,
)

/**
 * 审核/验收结论。
 * 两个动作的 true/false 语义不同：审核是「通过/驳回」，验收是「通过/退回」。
 */
const conclusion = computed(() => {
  if (preview.value.approved === undefined) return ''
  if (preview.value.action === 'ACCEPT') {
    return preview.value.approved ? '通过' : '退回'
  }
  if (preview.value.action === 'REVIEW') {
    return preview.value.approved ? '通过' : '驳回'
  }
  return preview.value.approved ? '通过' : '否定'
})

const canOpen = computed(
  () => props.canOpenOrder && preview.value.orderId !== undefined && preview.value.orderId > 0,
)

/** 令牌有效期提示；没有该字段（自然语言识别）就不显示 */
const expiresText = computed(() => {
  const seconds = preview.value.expiresInSeconds
  if (!seconds || seconds <= 0) return ''
  const minutes = Math.max(1, Math.round(seconds / 60))
  return `本次预览约 ${minutes} 分钟内有效`
})
</script>

<template>
  <section class="wconfirm" :class="{ 'is-plain': !structured }">
    <header class="wconfirm__head">
      <span class="wconfirm__icon">
        <el-icon :size="15"><Warning /></el-icon>
      </span>

      <div class="wconfirm__heading">
        <b>{{ title }}</b>

        <p v-if="structured" class="wconfirm__order">
          <span v-if="preview.orderNo" class="wo-num">{{ preview.orderNo }}</span>
          <span v-if="preview.title" class="wconfirm__order-title">{{ preview.title }}</span>
          <span v-if="!preview.orderNo && preview.orderId" class="wo-num">
            工单 ID {{ preview.orderId }}
          </span>
        </p>

        <p v-else class="wconfirm__hint">
          助手已给出预览，但还没有修改工单。核对无误后再确认执行。
        </p>
      </div>
    </header>

    <dl v-if="structured" class="wconfirm__facts">
      <div v-if="hasFlow" class="wconfirm__fact">
        <dt>状态变化</dt>
        <dd>
          <span>{{ fromStatusText }}</span>
          <i class="wconfirm__arrow">→</i>
          <b>{{ toStatusText }}</b>
          <span v-if="preview.action === 'TRANSFER'" class="wconfirm__sub">（更换处理人）</span>
        </dd>
      </div>

      <div v-if="conclusion" class="wconfirm__fact">
        <dt>结论</dt>
        <dd>{{ conclusion }}</dd>
      </div>

      <div v-if="preview.handlerId !== undefined" class="wconfirm__fact">
        <dt>处理人</dt>
        <dd class="wo-num">#{{ preview.handlerId }}</dd>
      </div>

      <div v-if="preview.remark" class="wconfirm__fact">
        <dt>备注</dt>
        <dd class="wconfirm__remark">{{ preview.remark }}</dd>
      </div>
    </dl>

    <footer class="wconfirm__foot">
      <p class="wconfirm__note">
        <span>点击后向助手发送「{{ WRITE_CONFIRM_KEYWORD }}」</span>
        <span v-if="expiresText"> · {{ expiresText }}</span>
        <span class="wconfirm__note-sub">实际写入仍由服务端校验权限与工单状态</span>
      </p>

      <div class="wconfirm__actions">
        <button v-if="canOpen" type="button" class="wconfirm__btn" @click="emit('open-order')">
          查看工单
        </button>

        <button
          type="button"
          class="wconfirm__btn wconfirm__btn--primary"
          :disabled="busy"
          @click="emit('confirm')"
        >
          <el-icon :size="14"><Select /></el-icon>
          <span>确认执行</span>
        </button>
      </div>
    </footer>
  </section>
</template>

<style scoped lang="scss">
.wconfirm {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid var(--wo-hairline-brand);
  border-radius: var(--wo-radius);
  background: var(--wo-brand-wash);
}

.wconfirm.is-plain {
  background: var(--wo-surface-sunken);
  border-color: var(--wo-hairline);
}

.wconfirm__head {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.wconfirm__icon {
  width: 24px;
  height: 24px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  background: var(--wo-surface);
  color: var(--wo-st-0-fg);
}

.wconfirm__heading {
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 3px;

  b {
    font-size: 13px;
    font-weight: 700;
    color: var(--wo-ink-1);
  }
}

.wconfirm__order {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: var(--wo-ink-2);
}

.wconfirm__order-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 100%;
}

.wconfirm__hint {
  font-size: 12px;
  line-height: 1.6;
  color: var(--wo-ink-3);
}

.wconfirm__facts {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin: 9px 0 0 33px;
}

.wconfirm__fact {
  display: flex;
  align-items: baseline;
  gap: 8px;
  font-size: 12px;
  line-height: 1.6;

  dt {
    flex: none;
    width: 56px;
    color: var(--wo-ink-4);
  }

  dd {
    min-width: 0;
    margin: 0;
    color: var(--wo-ink-2);
  }

  b {
    color: var(--wo-brand);
    font-weight: 600;
  }
}

.wconfirm__arrow {
  margin: 0 6px;
  font-style: normal;
  color: var(--wo-ink-4);
}

.wconfirm__sub {
  color: var(--wo-ink-4);
}

.wconfirm__remark {
  overflow-wrap: anywhere;
}

.wconfirm__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
  padding-top: 9px;
  border-top: 1px solid var(--wo-hairline);
}

.wconfirm__note {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
  font-size: 11px;
  line-height: 1.5;
  color: var(--wo-ink-4);
}

.wconfirm__note-sub {
  color: var(--wo-ink-4);
  opacity: 0.9;
}

.wconfirm__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex: none;
  margin-left: auto;
}

.wconfirm__btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border: 1px solid var(--wo-hairline-strong);
  border-radius: var(--wo-radius-sm);
  background: var(--wo-surface);
  color: var(--wo-ink-2);
  font-family: inherit;
  font-size: 12.5px;
  cursor: pointer;
  transition:
    color var(--wo-dur-fast) var(--wo-ease),
    border-color var(--wo-dur-fast) var(--wo-ease),
    background-color var(--wo-dur-fast) var(--wo-ease);

  &:hover:not(:disabled) {
    color: var(--wo-brand);
    border-color: var(--wo-hairline-brand);
  }

  &:disabled {
    opacity: 0.55;
    cursor: not-allowed;
  }
}

.wconfirm__btn--primary {
  border-color: var(--wo-brand);
  background: var(--wo-brand);
  color: #fff;
  font-weight: 600;

  &:hover:not(:disabled) {
    border-color: var(--wo-brand-hover);
    background: var(--wo-brand-hover);
    color: #fff;
  }
}

@media (max-width: 620px) {
  .wconfirm__facts {
    margin-left: 0;
  }

  .wconfirm__foot {
    align-items: flex-start;
  }
}
</style>
