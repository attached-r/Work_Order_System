<script setup lang="ts">
/**
 * 提交工单
 *
 * 表单按"必填 -> 选填 -> 明细"分成三块,每块一个小标题和一句说明,
 * 让填写者知道每段在问什么,而不是面对一长条没有分组的输入框。
 *
 * 资源明细是动态行,用 el-table 承载 + 行内编辑,可增删。
 * 右侧常驻填写指引与提交摘要(sticky),长表单滚动时始终可见。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
// 裸图标名不会被 resolver 自动解析,必须显式导入
import { Delete, Plus } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { ORDER_TYPE_OPTIONS, PRIORITY_OPTIONS, getOrderTypeLabel, getPriorityLabel } from '@/constants/workorder'
import { createWorkOrder, getWorkOrder, resubmitWorkOrder } from '@/api'
import type { WorkOrderSavePayload } from '@/api'
import { parseTime, toIsoLocal } from '@/utils/datetime'
import { useUserStore } from '@/stores/user'
import type { WorkOrderResourceForm } from '@/types/domain'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const submitting = ref(false)
const loading = ref(false)

/** 从详情页"重新提交"跳进来时带 ?from=id,用于回填原内容 */
const fromId = computed(() => {
  const raw = route.query.from
  const v = Array.isArray(raw) ? raw[0] : raw
  const n = Number(v)
  return Number.isFinite(n) && n > 0 ? n : null
})
const isResubmit = computed(() => fromId.value !== null)

const form = reactive({
  title: '',
  content: '',
  orderType: null as number | null,
  // 优先级不给 null:只有三档且默认"中",el-radio-group 的 modelValue 也不接受 null
  priority: 2,
  // 绑 Date 而不是字符串:后端只认 ISO 的 T 分隔写法,转换统一交给 toIsoLocal
  expireTime: null as Date | null,
  resources: [] as WorkOrderResourceForm[],
})

const rules: FormRules = {
  title: [
    { required: true, message: '请填写工单标题', trigger: 'blur' },
    { max: 200, message: '标题不能超过 200 个字符', trigger: 'blur' },
  ],
  orderType: [{ required: true, message: '请选择工单类型', trigger: 'change' }],
  priority: [{ required: true, message: '请选择优先级', trigger: 'change' }],
  content: [{ max: 5000, message: '描述不能超过 5000 个字符', trigger: 'blur' }],
}

/** 新增一行资源明细 */
function addResource() {
  form.resources.push({ resourceName: '', resourceType: null, quantity: 1, unit: null, remark: null })
}

function removeResource(index: number) {
  form.resources.splice(index, 1)
}

/**
 * 提交前把资源明细里的空行剔掉,避免后端收到无意义数据。
 * 只以「资源名称」判空:名称是这一行的主字段,填了名称就说明这行是有意的,
 * 此时若类别没填,由 handleSubmit 明确报错,而不是悄悄丢弃。
 */
const validResources = computed(() => form.resources.filter((r) => r.resourceName.trim()))

const contentLength = computed(() => form.content.length)

/** 重新提交时回填原工单内容 */
async function loadForResubmit() {
  if (fromId.value === null) return
  loading.value = true
  try {
    const d = await getWorkOrder(fromId.value)
    form.title = d.title
    form.content = d.content ?? ''
    form.orderType = d.orderType
    form.priority = d.priority
    form.expireTime = parseTime(d.expireTime)
    form.resources = d.resources.map((r) => ({
      resourceName: r.resourceName,
      resourceType: r.resourceType,
      quantity: r.quantity ?? 1,
      unit: r.unit,
      remark: r.remark,
    }))
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '原工单加载失败')
  } finally {
    loading.value = false
  }
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  // 后端也会挡,但来回一趟才知道不如本地先拦
  if (form.expireTime && form.expireTime.getTime() <= Date.now()) {
    ElMessage.warning('期望完成时间必须晚于当前时间')
    return
  }

  // 后端 WorkOrderResourceDTO.resourceType 是 @NotBlank,漏填只会换来一句
  // 「资源类别不能为空」,不如在这里指出是第几行
  const incomplete = validResources.value.findIndex((r) => !r.resourceType?.trim())
  if (incomplete !== -1) {
    ElMessage.warning(`资源明细第 ${incomplete + 1} 行未填写类别`)
    return
  }

  submitting.value = true
  try {
    const payload: WorkOrderSavePayload = {
      title: form.title,
      content: form.content || null,
      orderType: form.orderType as number,
      priority: form.priority,
      expireTime: form.expireTime ? toIsoLocal(form.expireTime) : null,
      resources: validResources.value.map((r) => ({
        resourceName: r.resourceName.trim(),
        resourceType: (r.resourceType ?? '').trim(),
        quantity: r.quantity,
        unit: r.unit,
        remark: r.remark,
      })),
    }

    if (isResubmit.value && fromId.value !== null) {
      await resubmitWorkOrder(fromId.value, payload)
    } else {
      await createWorkOrder(payload)
    }

    ElMessage.success(isResubmit.value ? '工单已重新提交' : '工单已提交,等待审核')
    router.push({ name: 'workorder-list' })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '提交失败,请稍后重试')
  } finally {
    submitting.value = false
  }
}

async function handleCancel() {
  try {
    await ElMessageBox.confirm('离开后已填写的内容会丢失,确定返回吗?', '放弃填写', {
      confirmButtonText: '确定返回',
      cancelButtonText: '继续填写',
      type: 'warning',
    })
  } catch {
    return
  }
  router.push({ name: 'workorder-list' })
}

/** 存草稿:本阶段仅提示,后续可接本地存储或后端草稿接口 */
function saveDraft() {
  ElMessage.info('草稿功能待接入后端后开放')
}

onMounted(loadForResubmit)
</script>

<template>
  <div class="create">
    <PageHeader
      :eyebrow="isResubmit ? 'Resubmit' : 'New Work Order'"
      :title="isResubmit ? '重新提交工单' : '提交工单'"
      :description="
        isResubmit
          ? '已回填原工单内容,修改后重新提交会回到「待审核」。'
          : '填写清楚的现象描述能显著缩短处理时间。提交后进入「待审核」,由本部门审核人处理。'
      "
    >
      <template #actions>
        <el-button text @click="handleCancel">取消</el-button>
        <el-button :loading="submitting" @click="handleSubmit">
          {{ isResubmit ? '重新提交' : '提交' }}
        </el-button>
      </template>
    </PageHeader>

    <div v-loading="loading" class="create__grid">
      <!-- ==================== 左:表单 ==================== -->
      <el-form
        ref="formRef"
        :model="form"
        :rules="rules"
        label-position="top"
        class="create__form"
        @submit.prevent="handleSubmit"
      >
        <!-- ---------- 区块 1:基本信息 ---------- -->
        <section class="panel wo-card">
          <header class="panel__head">
            <span class="panel__idx wo-num">01</span>
            <div>
              <h2>基本信息</h2>
              <p>一句话说清问题是什么,以及有多紧急。</p>
            </div>
          </header>

          <el-form-item label="工单标题" prop="title">
            <el-input
              v-model="form.title"
              placeholder="例:生产线 MES 系统扫码枪无法识别工单条码"
              maxlength="200"
              show-word-limit
              clearable
            />
          </el-form-item>

          <div class="panel__row">
            <el-form-item label="工单类型" prop="orderType">
              <el-select v-model="form.orderType" placeholder="请选择" style="width: 100%">
                <el-option v-for="t in ORDER_TYPE_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
              </el-select>
            </el-form-item>

            <el-form-item label="优先级" prop="priority">
              <!-- 优先级用按钮组而非下拉:只有三档,直接选中比展开更快 -->
              <el-radio-group v-model="form.priority" class="prio">
                <el-radio-button
                  v-for="p in PRIORITY_OPTIONS"
                  :key="p.value"
                  :value="p.value"
                  :class="`prio--${p.value}`"
                >
                  {{ p.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
          </div>

          <el-form-item label="期望完成时间">
            <!-- 不设 value-format:绑 Date 并由 toIsoLocal 转成后端认的 ISO 写法 -->
            <el-date-picker
              v-model="form.expireTime"
              type="datetime"
              placeholder="不填则默认 72 小时后到期"
              :disabled-date="(d: Date) => d.getTime() < Date.now() - 86_400_000"
              style="width: 100%"
            />
          </el-form-item>
        </section>

        <!-- ---------- 区块 2:详细描述 ---------- -->
        <section class="panel wo-card">
          <header class="panel__head">
            <span class="panel__idx wo-num">02</span>
            <div>
              <h2>详细描述</h2>
              <p>建议写清:发生时间、影响范围、已经尝试过的排查。</p>
            </div>
          </header>

          <el-form-item prop="content">
            <el-input
              v-model="form.content"
              type="textarea"
              :rows="8"
              maxlength="5000"
              placeholder="现象描述:
…
发生时间:
…
影响范围:
…
已尝试的排查:
…"
            />
            <div class="counter">
              <span class="wo-text-3">越具体,处理越快</span>
              <span class="wo-num wo-text-3">{{ contentLength }} / 5000</span>
            </div>
          </el-form-item>
        </section>

        <!-- ---------- 区块 3:资源明细 ---------- -->
        <section class="panel wo-card">
          <header class="panel__head">
            <span class="panel__idx wo-num">03</span>
            <div>
              <h2>
                资源明细
                <span class="panel__optional">选填</span>
              </h2>
              <p>涉及账号、服务器、证书等资源申请时填写,便于派单时评估。</p>
            </div>
          </header>

          <el-table v-if="form.resources.length" :data="form.resources" class="res-table">
            <el-table-column label="资源名称" min-width="190">
              <template #default="{ row }">
                <el-input v-model="row.resourceName" placeholder="例:测试环境 MySQL 实例" />
              </template>
            </el-table-column>

            <el-table-column label="类型" width="140">
              <template #default="{ row }">
                <el-input v-model="row.resourceType" placeholder="必填,如 数据库" />
              </template>
            </el-table-column>

            <el-table-column label="数量" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="1" :max="999" controls-position="right" style="width: 100%" />
              </template>
            </el-table-column>

            <el-table-column label="单位" width="100">
              <template #default="{ row }">
                <el-input v-model="row.unit" placeholder="台 / 个" />
              </template>
            </el-table-column>

            <el-table-column label="备注" min-width="150">
              <template #default="{ row }">
                <el-input v-model="row.remark" placeholder="选填" />
              </template>
            </el-table-column>

            <el-table-column width="56" align="center">
              <template #default="{ $index }">
                <el-button text type="danger" @click="removeResource($index)">
                  <el-icon><Delete /></el-icon>
                </el-button>
              </template>
            </el-table-column>
          </el-table>

          <p v-else class="wo-text-3 res-empty">暂无资源明细,如无需申请资源可跳过。</p>

          <el-button class="res-add" @click="addResource">
            <el-icon><Plus /></el-icon>
            添加一行
          </el-button>
        </section>

        <!-- ---------- 底部操作 ---------- -->
        <div class="create__foot">
          <el-button text @click="handleCancel">取消</el-button>
          <el-button @click="saveDraft">存草稿</el-button>
          <el-button type="primary" :loading="submitting" @click="handleSubmit">
            {{ isResubmit ? '重新提交' : '提交工单' }}
          </el-button>
        </div>
      </el-form>

      <!-- ==================== 右:指引 + 摘要 ==================== -->
      <aside class="create__side">
        <section class="side-card wo-card">
          <h2 class="side-card__title">提交摘要</h2>

          <dl class="summary">
            <div class="summary__row">
              <dt>标题</dt>
              <dd :class="{ 'is-empty': !form.title }">{{ form.title || '未填写' }}</dd>
            </div>
            <div class="summary__row">
              <dt>类型</dt>
              <dd :class="{ 'is-empty': form.orderType === null }">
                {{ form.orderType === null ? '未选择' : getOrderTypeLabel(form.orderType) }}
              </dd>
            </div>
            <div class="summary__row">
              <dt>优先级</dt>
              <dd>{{ getPriorityLabel(form.priority) }}</dd>
            </div>
            <div class="summary__row">
              <dt>资源明细</dt>
              <dd :class="{ 'is-empty': validResources.length === 0 }">
                {{ validResources.length ? `${validResources.length} 项` : '无' }}
              </dd>
            </div>
            <div class="summary__row">
              <dt>提单人</dt>
              <dd>{{ userStore.displayName }}</dd>
            </div>
            <div class="summary__row">
              <dt>提交后状态</dt>
              <dd>待审核</dd>
            </div>
          </dl>
        </section>

        <section class="side-card side-card--tip wo-card">
          <h2 class="side-card__title">填写指引</h2>
          <ul class="tips">
            <li>
              <b>标题</b>写"哪个系统 + 什么现象",不要只写"系统坏了"。
            </li>
            <li>
              <b>优先级</b>影响处理顺序:高 = 影响业务进行,中 = 影响效率,低 = 可延后。
            </li>
            <li>
              <b>超时时间</b>不填默认 72 小时,到期未处理工单会被标记「已超时」。
            </li>
            <li>
              <b>资源明细</b>只用于资源申请类工单,故障报修可跳过。
            </li>
          </ul>
        </section>
      </aside>
    </div>
  </div>
</template>

<style scoped lang="scss">
.create__grid {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
  gap: var(--wo-space-4);
  align-items: start;
}

.create__form {
  display: flex;
  flex-direction: column;
  gap: var(--wo-space-4);
  min-width: 0;
}

// ===========================================================================
// 分区面板
// ===========================================================================
.panel {
  padding: 22px 24px 8px;
}

.panel__head {
  display: flex;
  gap: 12px;
  margin-bottom: 20px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--wo-hairline);

  h2 {
    display: flex;
    align-items: center;
    gap: 8px;
    font-size: 14px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }

  p {
    margin-top: 3px;
    font-size: 12.5px;
    color: var(--wo-ink-3);
  }
}

// 序号:给长表单一个可数的结构感
.panel__idx {
  flex: none;
  width: 26px;
  height: 26px;
  display: grid;
  place-items: center;
  border-radius: 7px;
  background: var(--wo-brand-wash);
  color: var(--wo-brand-hover);
  font-size: 11px;
  font-weight: 700;
}

.panel__optional {
  padding: 1px 7px;
  border-radius: 999px;
  background: var(--wo-surface-sunken);
  color: var(--wo-ink-3);
  font-size: 10px;
  font-weight: 600;
}

.panel__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 20px;
}

// ---- 优先级按钮组:每一档用自己的语义色 ----
.prio {
  :deep(.el-radio-button__inner) {
    font-weight: 600;
  }
}

.prio--1 :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: var(--wo-pr-1-bg);
  border-color: var(--wo-pr-1-dot);
  color: var(--wo-pr-1-fg);
  box-shadow: -1px 0 0 0 var(--wo-pr-1-dot);
}

.prio--2 :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: var(--wo-pr-2-bg);
  border-color: var(--wo-pr-2-dot);
  color: var(--wo-pr-2-fg);
  box-shadow: -1px 0 0 0 var(--wo-pr-2-dot);
}

.prio--3 :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: var(--wo-pr-3-bg);
  border-color: var(--wo-pr-3-dot);
  color: var(--wo-pr-3-fg);
  box-shadow: -1px 0 0 0 var(--wo-pr-3-dot);
}

// 字数统计:右对齐的一行小字
.counter {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  margin-top: 6px;
  font-size: 12px;
}

// ---- 资源明细表格 ----
.res-table {
  width: 100%;
  margin-bottom: 12px;

  // 行内编辑的表格不需要 hover 高亮,会干扰输入
  :deep(.el-table__row:hover > td.el-table__cell:first-child) {
    box-shadow: none;
  }
}

.res-empty {
  font-size: 13px;
  padding: 16px 0;
  text-align: center;
  border: 1px dashed var(--wo-hairline-strong);
  border-radius: var(--wo-radius-sm);
  margin-bottom: 12px;
}

.res-add {
  margin-bottom: 14px;
}

// ---- 底部操作 ----
.create__foot {
  display: flex;
  justify-content: flex-end;
  gap: 0;
  padding: 16px 20px;
  background: var(--wo-surface);
  border: 1px solid var(--wo-hairline);
  border-radius: var(--wo-radius);
  box-shadow: var(--wo-shadow-xs);
}

// ===========================================================================
// 右侧栏
// ===========================================================================
.create__side {
  display: flex;
  flex-direction: column;
  gap: var(--wo-space-4);
  position: sticky;
  top: calc(var(--wo-header-h) + var(--wo-space-5));
}

.side-card {
  padding: 18px;
}

.side-card__title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wo-ink-1);
  padding-bottom: 10px;
  margin-bottom: 14px;
  border-bottom: 1px solid var(--wo-hairline);
}

.summary {
  margin: 0;
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.summary__row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  font-size: 13px;

  dt {
    color: var(--wo-ink-3);
    flex: none;
  }

  dd {
    margin: 0;
    color: var(--wo-ink-1);
    font-weight: 500;
    text-align: right;
    min-width: 0;
    overflow-wrap: anywhere;

    &.is-empty {
      color: var(--wo-ink-4);
      font-weight: 400;
    }
  }
}

// ---- 指引列表 ----
.tips {
  margin: 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 12px;

  li {
    position: relative;
    padding-left: 16px;
    font-size: 12.5px;
    line-height: 1.7;
    color: var(--wo-ink-2);
  }

  // 用小方块做项目符号,比圆点更有"文档"感
  li::before {
    content: '';
    position: absolute;
    left: 0;
    top: 8px;
    width: 5px;
    height: 5px;
    border-radius: 1.5px;
    background: var(--wo-hairline-brand);
  }

  b {
    color: var(--wo-ink-1);
    font-weight: 600;
  }
}

// ===========================================================================
// 响应式
// ===========================================================================
@media (max-width: 1080px) {
  .create__grid {
    grid-template-columns: 1fr;
  }

  .create__side {
    position: static;
  }
}

@media (max-width: 640px) {
  .panel__row {
    grid-template-columns: 1fr;
  }
}
</style>
