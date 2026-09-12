<script setup lang="ts">
/**
 * 部门管理
 *
 * 部门目前是平铺结构(无父子层级),所以用表格而不是树。
 * 如果后续 schema 支持 parent_id,这里换成 el-table 的 tree-props 即可。
 *
 * 删除前先查该部门下还有多少用户:有用户的部门不允许直接删,
 * 这是最容易造成"孤儿数据"的地方,拦在前端能让后端少处理一类异常。
 *
 * 两处数据来源的说明:
 *   - 部门列表**本页自己拉**(GET /department/list,含 remark / createTime)。
 *     不共用 useDepartmentDirectory 的那份目录:目录走的是 /department/directory,
 *     返回的精简字段里没有 remark,而本页要编辑备注。
 *     改完只需 invalidate() 通知那份目录失效,用户管理页的下拉与工单详情的部门名
 *     下次进页面就会重新拉;
 *   - 成员数没有对应接口,用用户目录在客户端聚合。该目录是全查不分页的,
 *     所以这个数字是准的,没有分页上限截断的问题。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
// 裸图标名不会被 resolver 自动解析,必须显式导入
import { OfficeBuilding, Plus } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import { createDepartment, deleteDepartment, listDepartments, updateDepartment } from '@/api'
import { useDepartmentDirectory, useUserDirectory } from '@/composables/useDirectories'
import type { Department } from '@/types/domain'

const loading = ref(false)
const rows = ref<Department[]>([])

// 只借它的 invalidate:本页改完部门,要通知共享目录里的部门名也过期了
const { invalidate: invalidateDepts } = useDepartmentDirectory()
// 只用来数各部门有多少人
const { ensure: ensureUsers, allUsers } = useUserDirectory()

/** 部门 id -> 人数,用于展示与删除校验 */
const memberCount = computed(() => {
  const m = new Map<number, number>()
  for (const u of allUsers.value) {
    if (u.departmentId === null) continue
    m.set(u.departmentId, (m.get(u.departmentId) ?? 0) + 1)
  }
  return m
})

function membersOf(id: number): number {
  return memberCount.value.get(id) ?? 0
}

async function load() {
  loading.value = true
  try {
    rows.value = await listDepartments()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '部门列表加载失败')
  } finally {
    loading.value = false
  }
}

/** 增删改之后:本页重拉,同时让共享目录失效(部门名在别处也有缓存) */
async function reload() {
  invalidateDepts()
  await load()
}

// ===========================================================================
// 新增 / 编辑
// ===========================================================================
const dialogVisible = ref(false)
const submitting = ref(false)
const editing = ref<Department | null>(null)
const formRef = ref<FormInstance>()

const form = reactive({
  deptCode: '',
  deptName: '',
  remark: '',
})

const rules: FormRules = {
  deptCode: [
    { required: true, message: '请输入部门编码', trigger: 'blur' },
    { max: 32, message: '编码不能超过 32 个字符', trigger: 'blur' },
    {
      pattern: /^[A-Z][A-Z0-9_]*$/,
      message: '建议使用大写字母、数字与下划线,如 RND',
      trigger: 'blur',
    },
  ],
  deptName: [
    { required: true, message: '请输入部门名称', trigger: 'blur' },
    { max: 64, message: '名称不能超过 64 个字符', trigger: 'blur' },
  ],
  remark: [{ max: 200, message: '说明不能超过 200 个字符', trigger: 'blur' }],
}

const dialogTitle = computed(() => (editing.value ? '编辑部门' : '新增部门'))

function openCreate() {
  editing.value = null
  form.deptCode = ''
  form.deptName = ''
  form.remark = ''
  dialogVisible.value = true
  void formRef.value?.clearValidate()
}

function openEdit(row: Department) {
  editing.value = row
  form.deptCode = row.deptCode
  form.deptName = row.deptName
  form.remark = row.remark ?? ''
  dialogVisible.value = true
  void formRef.value?.clearValidate()
}

async function submit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  const payload = {
    deptCode: form.deptCode.trim(),
    deptName: form.deptName.trim(),
    // 空串会被后端当成有效说明存下来,统一转 null
    remark: form.remark.trim() || null,
  }

  submitting.value = true
  try {
    if (editing.value) {
      await updateDepartment(editing.value.id, payload)
    } else {
      await createDepartment(payload)
    }
    ElMessage.success(editing.value ? '部门已更新' : '部门已创建')
    dialogVisible.value = false
    await reload()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '部门保存失败')
  } finally {
    submitting.value = false
  }
}

// ===========================================================================
// 删除
// ===========================================================================
async function remove(row: Department) {
  const count = membersOf(row.id)

  // 有成员时直接拦掉,不给出"确定要删吗"再报错的糟糕体验
  if (count > 0) {
    await ElMessageBox.alert(
      `「${row.deptName}」下还有 ${count} 名用户,请先把他们调整到其他部门再删除。`,
      '无法删除',
      { confirmButtonText: '知道了', type: 'warning' },
    )
    return
  }

  try {
    await ElMessageBox.confirm(`确定要删除「${row.deptName}」吗?该操作不可恢复。`, '删除部门', {
      confirmButtonText: '确定删除',
      cancelButtonText: '取消',
      type: 'warning',
    })
  } catch {
    return
  }

  try {
    await deleteDepartment(row.id)
    ElMessage.success('已删除')
    await reload()
  } catch (e) {
    // 后端还会再挡一道:仍被工单引用时同样拒绝删除
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

onMounted(() => {
  void load()
  // 数人数用的,失败也不影响部门本身
  void ensureUsers()
})
</script>

<template>
  <div class="depts">
    <PageHeader
      eyebrow="System"
      title="部门管理"
      description="部门决定了工单的可见范围与审核归属:用户只能看到本部门相关工单。"
    >
      <template #actions>
        <el-button type="primary" @click="openCreate">
          <el-icon><Plus /></el-icon>
          新增部门
        </el-button>
      </template>
    </PageHeader>

    <section class="table-card wo-card">
      <el-table v-loading="loading" :data="rows" row-key="id">
        <el-table-column label="部门" min-width="220">
          <template #default="{ row }">
            <div class="dept-cell">
              <span class="dept-cell__icon">
                <el-icon :size="15"><OfficeBuilding /></el-icon>
              </span>
              <div class="dept-cell__meta">
                <b>{{ row.deptName }}</b>
                <code class="dept-cell__code">{{ row.deptCode }}</code>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="成员数" width="120">
          <template #default="{ row }">
            <span class="members" :class="{ 'is-zero': membersOf(row.id) === 0 }">
              <b class="wo-num">{{ membersOf(row.id) }}</b> 人
            </span>
          </template>
        </el-table-column>

        <el-table-column label="说明" min-width="240">
          <template #default="{ row }">
            <span class="wo-text-3">{{ row.remark ?? '—' }}</span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="130" align="right">
          <!-- el-table 的插槽把 row 声明成 DefaultRow(Record<PropertyKey, any>),
               不是 Department,所以在调用点断言回来 -->
          <template #default="{ row }">
            <el-button text type="primary" @click="openEdit(row as Department)">编辑</el-button>
            <el-button text type="danger" @click="remove(row as Department)">删除</el-button>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="还没有部门,先创建一个吧" :image-size="80">
            <el-button type="primary" @click="openCreate">新增部门</el-button>
          </el-empty>
        </template>
      </el-table>

      <footer class="table-card__foot">
        <span class="wo-text-3">
          共 <b class="wo-num">{{ rows.length }}</b> 个部门
        </span>
      </footer>
    </section>

    <!-- ============ 新增 / 编辑弹窗 ============ -->
    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="460px" :close-on-click-modal="false">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="部门编码" prop="deptCode">
          <el-input
            v-model="form.deptCode"
            placeholder="例:RND"
            :disabled="editing !== null"
            maxlength="32"
          />
          <p v-if="editing" class="field-hint">编码创建后不建议修改,已与工单数据关联。</p>
        </el-form-item>

        <el-form-item label="部门名称" prop="deptName">
          <el-input v-model="form.deptName" placeholder="例:研发部" maxlength="64" show-word-limit />
        </el-form-item>

        <el-form-item label="说明" prop="remark">
          <el-input
            v-model="form.remark"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
            placeholder="这个部门负责什么(选填)"
          />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.table-card {
  overflow: hidden;
}

.dept-cell {
  display: flex;
  align-items: center;
  gap: 11px;
}

.dept-cell__icon {
  width: 32px;
  height: 32px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: var(--wo-brand-hover);
  background: var(--wo-brand-wash);
}

.dept-cell__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.35;

  b {
    font-size: 13.5px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }
}

.dept-cell__code {
  font-family: 'Plus Jakarta Sans Variable', ui-monospace, monospace;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--wo-ink-3);
}

.members {
  font-size: 12.5px;
  color: var(--wo-ink-2);

  b {
    font-size: 14px;
    font-weight: 700;
    color: var(--wo-ink-1);
  }

  // 空部门弱化,提示这是个可以删的部门
  &.is-zero {
    color: var(--wo-ink-4);

    b {
      color: var(--wo-ink-4);
    }
  }
}

.field-hint {
  margin-top: 5px;
  font-size: 11.5px;
  color: var(--wo-ink-3);
  line-height: 1.5;
}

.table-card__foot {
  padding: 14px 16px;
  border-top: 1px solid var(--wo-hairline);
  background: var(--wo-surface-raised);
  font-size: 13px;

  b {
    color: var(--wo-ink-1);
    font-weight: 700;
  }
}
</style>
