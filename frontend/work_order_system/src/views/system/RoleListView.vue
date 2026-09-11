<script setup lang="ts">
/**
 * 角色权限
 *
 * 布局上做了个取舍:不用表格,改用卡片网格。
 * 原因是角色只有 5 个且基本不变,卡片能把「角色名 + 说明 + 权限数」一次说清,
 * 比表格更适合这种"少而行宽"的数据。
 *
 * 权限分配用抽屉 + 权限树,比弹窗能容纳更深的层级。
 */
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElTree } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { MOCK_PERMISSION_TREE, MOCK_ROLE_PERM_IDS, MOCK_ROLES } from '@/mock'
import type { PermissionVO, Role } from '@/types/domain'

const loading = ref(false)

/** 角色 id -> 已勾选权限 id,本地维护一份便于看到改动效果 */
const rolePermIds = ref<Record<number, number[]>>({ ...MOCK_ROLE_PERM_IDS })

/** 权限码 -> 名称,用于卡片上展示"主要权限"摘要 */
const permNameMap = computed(() => {
  const m = new Map<number, string>()
  const walk = (nodes: PermissionVO[]) => {
    for (const n of nodes) {
      m.set(n.id, n.permName)
      walk(n.children)
    }
  }
  walk(MOCK_PERMISSION_TREE)
  return m
})

/** 权限总数(不含分组节点) */
const totalPermCount = computed(() => {
  let count = 0
  const walk = (nodes: PermissionVO[]) => {
    for (const n of nodes) {
      if (n.children.length === 0) count += 1
      else walk(n.children)
    }
  }
  walk(MOCK_PERMISSION_TREE)
  return count
})

function permCountOf(roleId: number): number {
  return (rolePermIds.value[roleId] ?? []).length
}

/** 卡片上展示前 4 个权限名 */
function topPermsOf(roleId: number): string[] {
  const ids = rolePermIds.value[roleId] ?? []
  return ids
    .map((id) => permNameMap.value.get(id))
    .filter((n): n is string => Boolean(n))
    .slice(0, 4)
}

// TODO(api): 换成 GET /role/list
async function load() {
  loading.value = true
  try {
    await new Promise((r) => setTimeout(r, 180))
  } finally {
    loading.value = false
  }
}

// ===========================================================================
// 权限分配抽屉
// ===========================================================================
const drawerVisible = ref(false)
const drawerSubmitting = ref(false)
const currentRole = ref<Role | null>(null)
const treeRef = ref<InstanceType<typeof ElTree>>()

/** 树控件的默认勾选值 */
const defaultChecked = ref<number[]>([])

function openPermissionDrawer(role: Role) {
  currentRole.value = role
  defaultChecked.value = [...(rolePermIds.value[role.id] ?? [])]
  drawerVisible.value = true
}

async function submitPermissions() {
  if (!currentRole.value || !treeRef.value) return

  // 勾选态需要合并「全选」与「半选」:半选的父节点本身是被引用的分组,也要存下来
  const checked = treeRef.value.getCheckedKeys(false) as number[]
  const halfChecked = treeRef.value.getHalfCheckedKeys() as number[]
  rolePermIds.value[currentRole.value.id] = [...checked, ...halfChecked]

  drawerSubmitting.value = true
  try {
    // TODO(api): PUT /role/{roleId}/permissions
    await new Promise((r) => setTimeout(r, 380))
    ElMessage.success(`「${currentRole.value.roleName}」的权限已更新(演示环境未真正落库)`)
    drawerVisible.value = false
  } finally {
    drawerSubmitting.value = false
  }
}

function toggleAll(checked: boolean) {
  if (!treeRef.value) return
  if (checked) {
    treeRef.value.setCheckedKeys(allLeafIds.value)
  } else {
    treeRef.value.setCheckedKeys([])
  }
}

/** 全部叶子节点 id,用于"全选" */
const allLeafIds = computed(() => {
  const ids: number[] = []
  const walk = (nodes: PermissionVO[]) => {
    for (const n of nodes) {
      if (n.children.length === 0) ids.push(n.id)
      else walk(n.children)
    }
  }
  walk(MOCK_PERMISSION_TREE)
  return ids
})

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="roles">
    <PageHeader
      eyebrow="System"
      title="角色权限"
      description="角色是权限的集合。用户的最终权限 = 其所有角色权限的并集。"
    />

    <!-- ============ 角色卡片 ============ -->
    <section class="role-grid">
      <article v-for="role in MOCK_ROLES" :key="role.id" class="role-card wo-card">
        <header class="role-card__head">
          <div class="role-card__title">
            <h2>{{ role.roleName }}</h2>
            <code class="role-card__code">{{ role.roleCode }}</code>
          </div>

          <span class="role-card__count" :class="{ 'is-full': permCountOf(role.id) === totalPermCount }">
            <b class="wo-num">{{ permCountOf(role.id) }}</b>
            <i>/ {{ totalPermCount }}</i>
          </span>
        </header>

        <p class="role-card__remark">{{ role.remark ?? '暂无说明' }}</p>

        <div class="role-card__perms">
          <span v-for="p in topPermsOf(role.id)" :key="p" class="perm-chip">{{ p }}</span>
          <span v-if="permCountOf(role.id) > 4" class="perm-chip perm-chip--more">
            +{{ permCountOf(role.id) - 4 }}
          </span>
          <span v-if="permCountOf(role.id) === 0" class="wo-text-3 role-card__none">未分配任何权限</span>
        </div>

        <footer class="role-card__foot">
          <el-button text type="primary" @click="openPermissionDrawer(role)">配置权限</el-button>
        </footer>
      </article>
    </section>

    <!-- ============ 权限分配抽屉 ============ -->
    <el-drawer v-model="drawerVisible" size="440px" :title="`配置权限 · ${currentRole?.roleName ?? ''}`">
      <div class="drawer">
        <div class="drawer__bar">
          <span class="wo-text-3">
            勾选该角色可执行的操作,共
            <b class="wo-num">{{ totalPermCount }}</b> 项
          </span>
          <div class="drawer__quick">
            <el-button text size="small" @click="toggleAll(true)">全选</el-button>
            <el-button text size="small" @click="toggleAll(false)">清空</el-button>
          </div>
        </div>

        <el-tree
          ref="treeRef"
          :data="MOCK_PERMISSION_TREE"
          node-key="id"
          show-checkbox
          default-expand-all
          :default-checked-keys="defaultChecked"
          :props="{ label: 'permName', children: 'children' }"
          class="perm-tree"
        >
          <template #default="{ data }">
            <span class="tree-node">
              <span class="tree-node__name">{{ data.permName }}</span>
              <code class="tree-node__code">{{ data.permCode }}</code>
            </span>
          </template>
        </el-tree>
      </div>

      <template #footer>
        <div class="drawer__foot">
          <el-button @click="drawerVisible = false">取消</el-button>
          <el-button type="primary" :loading="drawerSubmitting" @click="submitPermissions">
            保存
          </el-button>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped lang="scss">
// ===========================================================================
// 角色卡片网格
// ===========================================================================
.role-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: var(--wo-space-4);
}

.role-card {
  display: flex;
  flex-direction: column;
  padding: 20px;
  transition:
    box-shadow var(--wo-dur) var(--wo-ease),
    border-color var(--wo-dur) var(--wo-ease);

  &:hover {
    border-color: var(--wo-hairline-brand);
    box-shadow: var(--wo-shadow-sm);
  }
}

.role-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.role-card__title {
  min-width: 0;

  h2 {
    font-size: 16px;
    font-weight: 700;
    letter-spacing: -0.01em;
    color: var(--wo-ink-1);
  }
}

.role-card__code {
  display: inline-block;
  margin-top: 4px;
  font-family: 'Plus Jakarta Sans Variable', ui-monospace, monospace;
  font-size: 10.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--wo-ink-3);
  background: var(--wo-surface-sunken);
  padding: 1px 7px;
  border-radius: 4px;
}

// 权限计数:满权限时用主色强调
.role-card__count {
  flex: none;
  display: flex;
  align-items: baseline;
  gap: 3px;
  color: var(--wo-ink-3);

  b {
    font-size: 20px;
    font-weight: 700;
    letter-spacing: -0.02em;
    color: var(--wo-ink-2);
  }

  i {
    font-style: normal;
    font-size: 11px;
  }

  &.is-full b {
    color: var(--wo-brand);
  }
}

.role-card__remark {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--wo-ink-3);
  // 说明文字长短不一,固定两行高度让卡片底部对齐
  min-height: 41px;
}

.role-card__perms {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
  margin: 14px 0 16px;
  min-height: 24px;
}

.perm-chip {
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--wo-brand-wash);
  color: var(--wo-brand-hover);
  font-size: 11.5px;
  font-weight: 500;
  white-space: nowrap;
}

.perm-chip--more {
  background: var(--wo-surface-sunken);
  color: var(--wo-ink-3);
}

.role-card__none {
  font-size: 12px;
}

.role-card__foot {
  margin-top: auto;
  padding-top: 14px;
  border-top: 1px solid var(--wo-hairline);

  :deep(.el-button) {
    padding-left: 0;
  }
}

// ===========================================================================
// 抽屉
// ===========================================================================
.drawer {
  padding: 18px 20px;
}

.drawer__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 8px;
  border-bottom: 1px solid var(--wo-hairline);
  font-size: 12.5px;

  b {
    color: var(--wo-ink-1);
    font-weight: 700;
  }
}

.drawer__quick {
  display: flex;
  gap: 2px;
}

.perm-tree {
  background: transparent;
}

.tree-node {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.tree-node__name {
  font-size: 13px;
}

.tree-node__code {
  font-family: 'Plus Jakarta Sans Variable', ui-monospace, monospace;
  font-size: 10px;
  color: var(--wo-ink-4);
  letter-spacing: 0.03em;
}

.drawer__foot {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 14px 20px;
  border-top: 1px solid var(--wo-hairline);
}
</style>
