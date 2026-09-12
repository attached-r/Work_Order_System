<script setup lang="ts">
/**
 * 用户管理
 *
 * 行内操作较多(分配角色/调整部门/重置密码/启停用/编辑/删除),
 * 所以把低频且危险的动作收进「更多」下拉,只把"分配角色"留在外面,
 * 避免每行挂 6 个按钮把表格压得没法看。
 *
 * 筛选全在服务端做(/user/page 支持 keyword / departmentId / status / roleCode),
 * 不在页内过滤 —— 那样只会得到"这一页里恰好符合条件的人",是误导。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// 裸图标名不会被 resolver 自动解析,必须显式导入
import { ArrowDown, Delete, Edit, Key, OfficeBuilding, Search, SwitchButton } from '@element-plus/icons-vue'
import PageHeader from '@/components/PageHeader.vue'
import {
  assignDepartment,
  assignRoles,
  deleteUser,
  pageUsers,
  resetPassword,
  updateUser,
  updateUserStatus,
} from '@/api'
import { useDepartmentDirectory, useRoleDirectory } from '@/composables/useDirectories'
import { getRoleName } from '@/constants/role'
import { useUserStore } from '@/stores/user'
import type { UserVO } from '@/types/domain'

const userStore = useUserStore()
// 部门下拉的选项与角色弹窗的候选项
const { ensure: ensureDepts, departments } = useDepartmentDirectory()
const { ensure: ensureRoles, roles } = useRoleDirectory()

/** 只读地挡一下越权操作:不能停用/删除自己 */
const myId = computed(() => userStore.user?.userId ?? -1)

const loading = ref(false)
const rows = ref<UserVO[]>([])
const total = ref(0)

const query = reactive({
  current: 1,
  size: 10,
  keyword: '',
  departmentId: null as number | null,
  status: null as number | null,
})

async function load() {
  loading.value = true
  try {
    const res = await pageUsers({
      current: query.current,
      size: query.size,
      keyword: query.keyword,
      departmentId: query.departmentId,
      status: query.status,
    })
    rows.value = res.records
    total.value = res.total
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '用户列表加载失败')
  } finally {
    loading.value = false
  }
}

function search() {
  query.current = 1
  void load()
}

function resetFilters() {
  query.keyword = ''
  query.departmentId = null
  query.status = null
  query.current = 1
  void load()
}

// ===========================================================================
// 分配角色
// ===========================================================================
/**
 * ⚠️ 两边的标识不一致,这里必须做一次换算:
 *   - 读:UserVO.roles 是角色**标识**列表,如 ['SUBMITTER'](给人看的);
 *   - 写:PUT /user/{userId}/roles 收的是角色**主键 id** 列表(给库用的)。
 * 勾选框用 id 做 value,所以回显时要把 roles 里的标识翻成 id,
 * 找不到对应角色的标识(角色表被改过)直接忽略,不让整页崩掉。
 */
const roleDialog = ref(false)
const roleSubmitting = ref(false)
const roleTarget = ref<UserVO | null>(null)
const selectedRoleIds = ref<number[]>([])

function openRoleDialog(row: UserVO) {
  roleTarget.value = row
  const codeToId = new Map(roles.value.map((r) => [r.roleCode, r.id]))
  selectedRoleIds.value = row.roles
    .map((code) => codeToId.get(code))
    .filter((id): id is number => id !== undefined)
  roleDialog.value = true
}

async function submitRoles() {
  if (!roleTarget.value) return
  // 后端要求至少一个角色,先拦下来免得白跑一趟
  if (selectedRoleIds.value.length === 0) {
    ElMessage.warning('请至少选择一个角色')
    return
  }

  roleSubmitting.value = true
  try {
    await assignRoles(roleTarget.value.userId, selectedRoleIds.value)
    ElMessage.success(`已更新 ${roleTarget.value.realName} 的角色`)
    roleDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '角色保存失败')
  } finally {
    roleSubmitting.value = false
  }
}

// ===========================================================================
// 调整部门
// ===========================================================================
/**
 * ⚠️ 这里没有「全局」选项:AssignDepartmentDTO.departmentId 是 @NotNull,
 * 后端不接受把部门清空;UpdateUserDTO 的同名字段又是「null 即不改」的语义,
 * 也清不掉。也就是说「取消归属」这条路径接口层面不存在,所以下拉不做 clearable,
 * 选了就是换一个部门。
 */
const deptDialog = ref(false)
const deptSubmitting = ref(false)
const deptTarget = ref<UserVO | null>(null)
const selectedDept = ref<number | null>(null)

function openDeptDialog(row: UserVO) {
  deptTarget.value = row
  selectedDept.value = row.departmentId
  deptDialog.value = true
}

async function submitDept() {
  if (!deptTarget.value) return
  if (selectedDept.value === null) {
    ElMessage.warning('请选择部门')
    return
  }
  deptSubmitting.value = true
  try {
    await assignDepartment(deptTarget.value.userId, selectedDept.value)
    ElMessage.success('部门已调整')
    deptDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '部门调整失败')
  } finally {
    deptSubmitting.value = false
  }
}

// ===========================================================================
// 重置密码
// ===========================================================================
const pwdDialog = ref(false)
const pwdSubmitting = ref(false)
const pwdTarget = ref<UserVO | null>(null)
const pwdForm = reactive({ password: '', confirm: '' })

function openPwdDialog(row: UserVO) {
  pwdTarget.value = row
  pwdForm.password = ''
  pwdForm.confirm = ''
  pwdDialog.value = true
}

async function submitPwd() {
  if (!pwdTarget.value) return
  if (pwdForm.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  if (pwdForm.password !== pwdForm.confirm) {
    ElMessage.warning('两次输入的密码不一致')
    return
  }

  pwdSubmitting.value = true
  try {
    await resetPassword(pwdTarget.value.userId, pwdForm.password)
    ElMessage.success('密码已重置,该用户需重新登录')
    pwdDialog.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '密码重置失败')
  } finally {
    pwdSubmitting.value = false
  }
}

// ===========================================================================
// 编辑用户
// ===========================================================================
/**
 * 只有姓名和手机号两项:
 *   - 部门不放在这里,免得和上面的「调整部门」两个入口做同一件事,
 *     而且那个字段在 UpdateUserDTO 里是"null 即不改",在这个表单里语义很别扭;
 *   - 手机号现在可读可写:打开时回填 row.phone,清空输入框即清空手机号。
 *
 * 手机号是三态语义(见 UpdateUserPayload),提交时的映射关系:
 *   输入框非空 → 传 trim 后的值(覆盖)
 *   输入框为空 → 传 `''`(清空)
 * 不再有"留空表示不修改"这个分支 —— 想不改就别动这个框,反正它已经回填了原值。
 */
const editDialog = ref(false)
const editSubmitting = ref(false)
const editTarget = ref<UserVO | null>(null)
const editForm = reactive({ realName: '', phone: '' })

function openEditDialog(row: UserVO) {
  editTarget.value = row
  editForm.realName = row.realName
  // 后端清空后库里存的是空串,统一归一成空串显示,免得 null 在输入框里显示成 "null"
  editForm.phone = row.phone ?? ''
  editDialog.value = true
}

async function submitEdit() {
  if (!editTarget.value) return
  if (!editForm.realName.trim()) {
    ElMessage.warning('请填写真实姓名')
    return
  }

  editSubmitting.value = true
  try {
    await updateUser(editTarget.value.userId, {
      realName: editForm.realName.trim(),
      // 空串是"清空"而不是"不修改",这正是我们要的:框里已有原值,清掉它就是用户的明确意图
      phone: editForm.phone.trim(),
    })
    ElMessage.success('用户信息已更新')
    editDialog.value = false
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '用户信息保存失败')
  } finally {
    editSubmitting.value = false
  }
}

// ===========================================================================
// 启停用 / 删除
// ===========================================================================
async function toggleStatus(row: UserVO) {
  const next = row.status === 1 ? 0 : 1
  const verb = next === 1 ? '启用' : '停用'

  try {
    await ElMessageBox.confirm(`确定要${verb}「${row.realName}」的账号吗?`, `${verb}账号`, {
      confirmButtonText: `确定${verb}`,
      cancelButtonText: '取消',
      type: next === 1 ? 'info' : 'warning',
    })
  } catch {
    return
  }

  try {
    await updateUserStatus(row.userId, next)
    ElMessage.success(`已${verb}`)
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : `${verb}失败`)
  }
}

async function removeUser(row: UserVO) {
  try {
    await ElMessageBox.confirm(
      `删除后该用户将无法登录,其历史工单仍会保留。确定删除「${row.realName}」吗?`,
      '删除用户',
      { confirmButtonText: '确定删除', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }

  try {
    await deleteUser(row.userId)
    ElMessage.success('已删除')
    await load()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败')
  }
}

function handleCommand(command: string, row: UserVO) {
  switch (command) {
    case 'edit':
      openEditDialog(row)
      break
    case 'dept':
      openDeptDialog(row)
      break
    case 'pwd':
      openPwdDialog(row)
      break
    case 'status':
      void toggleStatus(row)
      break
    case 'delete':
      void removeUser(row)
      break
  }
}

onMounted(() => {
  void load()
  // 分配角色 / 调整部门两个弹窗要用到，提前备好
  void ensureRoles()
  void ensureDepts()
})
</script>

<template>
  <div class="users">
    <PageHeader
      eyebrow="System"
      title="用户管理"
      description="管理系统账号、所属部门与角色。角色决定用户能看到和操作哪些工单。"
    />

    <!-- ============ 条件栏 ============ -->
    <section class="filters wo-card">
      <el-input
        v-model="query.keyword"
        placeholder="搜索用户名或姓名"
        clearable
        class="filters__search"
        @keyup.enter="search"
        @clear="search"
      >
        <template #prefix>
          <el-icon><Search /></el-icon>
        </template>
      </el-input>

      <el-select
        v-model="query.departmentId"
        placeholder="全部部门"
        clearable
        class="filters__select"
        @change="search"
      >
        <el-option v-for="d in departments" :key="d.id" :label="d.deptName" :value="d.id" />
      </el-select>

      <el-select
        v-model="query.status"
        placeholder="全部状态"
        clearable
        class="filters__select"
        @change="search"
      >
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>

      <el-button @click="search">查询</el-button>
      <el-button text @click="resetFilters">重置</el-button>
    </section>

    <!-- ============ 表格 ============ -->
    <section class="table-card wo-card">
      <el-table v-loading="loading" :data="rows" row-key="userId">
        <el-table-column label="用户" min-width="200">
          <template #default="{ row }">
            <div class="user-cell">
              <span class="user-cell__avatar">{{ row.realName.slice(0, 1) }}</span>
              <div class="user-cell__meta">
                <b>{{ row.realName }}</b>
                <i class="wo-num">{{ row.username }}</i>
              </div>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="所属部门" width="130">
          <!-- UserVO 自带 departmentName,不必再查部门目录 -->
          <template #default="{ row }">
            <span :class="row.departmentName ? 'wo-text-2' : 'wo-text-3'">
              {{ row.departmentName ?? '全局' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="角色" min-width="180">
          <template #default="{ row }">
            <span v-if="row.roles.length" class="role-list">
              <el-tag v-for="r in row.roles" :key="r" size="small" type="info">{{ getRoleName(r) }}</el-tag>
            </span>
            <span v-else class="wo-text-3">未分配</span>
          </template>
        </el-table-column>

        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <span class="status" :class="row.status === 1 ? 'is-on' : 'is-off'">
              <i aria-hidden="true" />
              {{ row.status === 1 ? '启用' : '停用' }}
            </span>
          </template>
        </el-table-column>

        <el-table-column label="操作" width="180" align="right">
          <!-- el-table 的插槽把 row 声明成 DefaultRow(Record<PropertyKey, any>),
               不是 UserVO,所以在调用点断言回来 -->
          <template #default="{ row }">
            <el-button text type="primary" @click="openRoleDialog(row as UserVO)">分配角色</el-button>

            <el-dropdown trigger="click" @command="(c: string) => handleCommand(c, row as UserVO)">
              <el-button text class="more">
                更多
                <el-icon :size="12"><ArrowDown /></el-icon>
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="edit">
                    <el-icon><Edit /></el-icon>编辑信息
                  </el-dropdown-item>
                  <el-dropdown-item command="dept">
                    <el-icon><OfficeBuilding /></el-icon>调整部门
                  </el-dropdown-item>
                  <el-dropdown-item command="pwd">
                    <el-icon><Key /></el-icon>重置密码
                  </el-dropdown-item>
                  <el-dropdown-item command="status" divided :disabled="row.userId === myId">
                    <el-icon><SwitchButton /></el-icon>
                    {{ row.status === 1 ? '停用账号' : '启用账号' }}
                  </el-dropdown-item>
                  <el-dropdown-item command="delete" :disabled="row.userId === myId">
                    <span class="danger-item"><el-icon><Delete /></el-icon>删除用户</span>
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>

        <template #empty>
          <el-empty description="没有匹配的用户">
            <el-button v-if="query.keyword" @click="resetFilters">清除筛选条件</el-button>
          </el-empty>
        </template>
      </el-table>

      <footer class="table-card__foot">
        <span class="wo-text-3">
          共 <b class="wo-num">{{ total }}</b> 个用户
        </span>
        <el-pagination
          v-model:current-page="query.current"
          v-model:page-size="query.size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="sizes, prev, pager, next"
          background
          @current-change="load"
          @size-change="search"
        />
      </footer>
    </section>

    <!-- ============ 分配角色 ============ -->
    <el-dialog v-model="roleDialog" title="分配角色" width="480px">
      <p class="dialog-hint">
        为 <b>{{ roleTarget?.realName }}</b> 勾选角色,权限按角色的并集生效。
      </p>

      <!-- value 用角色主键 id:提交给 /user/{id}/roles 的是 id,不是角色标识 -->
      <el-checkbox-group v-model="selectedRoleIds" class="role-group">
        <label v-for="r in roles" :key="r.id" class="role-item">
          <el-checkbox :value="r.id">
            <b>{{ r.roleName }}</b>
            <i>{{ r.remark }}</i>
          </el-checkbox>
        </label>
      </el-checkbox-group>

      <template #footer>
        <el-button @click="roleDialog = false">取消</el-button>
        <el-button type="primary" :loading="roleSubmitting" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 调整部门 ============ -->
    <el-dialog v-model="deptDialog" title="调整所属部门" width="440px">
      <p class="dialog-hint">
        工单的可见范围按部门隔离。只能换一个部门,不能取消归属 ——
        接口不接受空的部门ID。
      </p>
      <el-select v-model="selectedDept" placeholder="请选择部门" style="width: 100%">
        <el-option v-for="d in departments" :key="d.id" :label="d.deptName" :value="d.id" />
      </el-select>

      <template #footer>
        <el-button @click="deptDialog = false">取消</el-button>
        <el-button type="primary" :loading="deptSubmitting" @click="submitDept">保存</el-button>
      </template>
    </el-dialog>

    <!-- ============ 重置密码 ============ -->
    <el-dialog v-model="pwdDialog" title="重置密码" width="440px">
      <p class="dialog-hint">
        正在重置 <b>{{ pwdTarget?.realName }}</b> 的密码,重置后需告知用户新密码。
      </p>
      <el-form label-position="top">
        <el-form-item label="新密码">
          <el-input v-model="pwdForm.password" type="password" placeholder="至少 6 位" show-password />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input v-model="pwdForm.confirm" type="password" placeholder="再输一次" show-password />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="pwdDialog = false">取消</el-button>
        <el-button type="primary" :loading="pwdSubmitting" @click="submitPwd">重置</el-button>
      </template>
    </el-dialog>

    <!-- ============ 编辑用户 ============ -->
    <el-dialog v-model="editDialog" title="编辑用户信息" width="440px">
      <el-form label-position="top">
        <el-form-item label="真实姓名" required>
          <el-input v-model="editForm.realName" placeholder="用于工单流转中展示" />
        </el-form-item>
        <el-form-item label="手机号">
          <!-- 清空输入框即清空手机号(后端按空串=清空处理) -->
          <el-input v-model="editForm.phone" placeholder="清空此项可删除手机号" clearable />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" :loading="editSubmitting" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped lang="scss">
.filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  margin-bottom: var(--wo-space-4);
}

.filters__search {
  width: 260px;
}

.filters__select {
  width: 148px;

  @media (max-width: 720px) {
    width: calc(50% - 5px);
  }
}

.table-card {
  overflow: hidden;
}

// ---- 用户单元格 ----
.user-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.user-cell__avatar {
  width: 32px;
  height: 32px;
  flex: none;
  display: grid;
  place-items: center;
  border-radius: 50%;
  font-size: 13px;
  font-weight: 700;
  color: var(--wo-brand-hover);
  background: var(--wo-brand-wash);
}

.user-cell__meta {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
  min-width: 0;

  b {
    font-size: 13.5px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }

  i {
    font-style: normal;
    font-size: 11.5px;
    color: var(--wo-ink-3);
  }
}

.role-list {
  display: inline-flex;
  flex-wrap: wrap;
  gap: 4px;
}

// ---- 状态:圆点 + 文字,与工单状态标签保持同一套语言 ----
.status {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 500;

  i {
    width: 6px;
    height: 6px;
    border-radius: 50%;
  }

  &.is-on {
    color: var(--wo-st-4-fg);
    i {
      background: var(--wo-st-4-dot);
    }
  }

  &.is-off {
    color: var(--wo-ink-3);
    i {
      background: var(--wo-ink-4);
    }
  }
}

.more {
  margin-left: 4px;
}

.danger-item {
  color: var(--wo-st-5-fg);
}

// ---- 弹窗内 ----
.dialog-hint {
  font-size: 13px;
  color: var(--wo-ink-2);
  margin-bottom: 16px;
  line-height: 1.6;

  b {
    color: var(--wo-ink-1);
  }
}

.role-group {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}

.role-item {
  padding: 10px 12px;
  border: 1px solid var(--wo-hairline);
  border-radius: var(--wo-radius-sm);
  background: var(--wo-surface-raised);
  cursor: pointer;
  transition: all var(--wo-dur-fast) var(--wo-ease);

  &:hover {
    border-color: var(--wo-hairline-brand);
    background: var(--wo-brand-wash);
  }

  :deep(.el-checkbox) {
    width: 100%;
    height: auto;
    align-items: flex-start;
  }

  :deep(.el-checkbox__label) {
    display: flex;
    flex-direction: column;
    gap: 2px;
    white-space: normal;
    line-height: 1.5;
  }

  b {
    font-size: 13px;
    font-weight: 600;
    color: var(--wo-ink-1);
  }

  i {
    font-style: normal;
    font-size: 11.5px;
    color: var(--wo-ink-3);
  }
}

.table-card__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wo-space-4);
  flex-wrap: wrap;
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
