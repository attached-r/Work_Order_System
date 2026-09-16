<script setup lang="ts">
/**
 * 角色权限
 *
 * 布局上做了个取舍:不用表格,改用卡片网格。
 * 原因是角色只有 5 个且基本不变,卡片能把「角色名 + 角色标识 + 说明」一次说清,
 * 比表格更适合这种"少而行宽"的数据。
 *
 * 读写成对:打开抽屉时先 GET /role/{roleId}/permissions 读回已分配的权限 ID,
 * 再让管理员改,保存走覆盖式的 PUT。**读回来的 id 必须先与权限字典求交集再勾选**,
 * 见 openPermissionDrawer —— 树控件的 default-checked-keys 只在创建时生效,
 * 且要是把字典里不存在的 id 塞进去,会出现"勾了但界面上看不到"的幽灵权限。
 *
 * 另外权限接口返回的是扁平列表(parent_id 全是 null),分组节点由前端按
 * permCode 的命名空间补出来,见 groupedTree。
 */
import { computed, nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
// ⚠️ 这里绝不能 import ElTree 这个「值」:一旦显式导入,unplugin 的 resolver 就
// 认不出模板里的 <el-tree>,tree.scss 不会被按需注入 —— 树会塌成没有 flex 的裸布局,
// 复选框和节点文字叠在一起。只需要它的实例类型,所以用 type-only 导入。
import type { TreeInstance } from 'element-plus'
import PageHeader from '@/components/PageHeader.vue'
import { assignPermissions, fetchPermissionTree, listRolePermissions, listRoles } from '@/api'
import type { PermissionVO, Role } from '@/types/domain'

const loading = ref(false)
const roles = ref<Role[]>([])
const permissions = ref<PermissionVO[]>([])

async function load() {
  loading.value = true
  try {
    // 两个都是只读接口,并发拉
    const [roleList, permList] = await Promise.all([listRoles(), fetchPermissionTree()])
    roles.value = roleList
    permissions.value = permList
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '角色或权限加载失败')
  } finally {
    loading.value = false
  }
}

/** 真实权限 id 集合 —— 用来把下面补出来的分组节点挡在提交之外 */
const realPermIds = computed(() => new Set(permissions.value.map((p) => p.id)))

const totalPermCount = computed(() => permissions.value.length)

/**
 * 命名空间 -> 分组标题。
 * permCode 形如 `workorder:review`,取冒号前那段当分组。
 */
const GROUP_LABELS: Record<string, string> = {
  workorder: '工单权限',
  user: '系统权限',
}

/**
 * 把扁平权限列表补成分组树。
 *
 * ⚠️ 分组必须「全覆盖」:抽屉里没出现的权限,保存时就会被覆盖掉,
 * 所以认不出前缀的一律落进「其它」,绝不能因为不认识就丢掉。
 *
 * 分组节点用**负数** id:真实权限 id 是自增正整数,不会撞上;
 * 提交时再按 realPermIds 过滤掉它们,免得把假 id 当成权限 id 发给后端。
 */
const groupedTree = computed<PermissionVO[]>(() => {
  const groups = new Map<string, PermissionVO[]>()
  for (const p of permissions.value) {
    const ns = p.permCode.split(':')[0] || '其它'
    const bucket = groups.get(ns)
    if (bucket) bucket.push(p)
    else groups.set(ns, [p])
  }

  return [...groups.entries()].map(([ns, children], i) => ({
    id: -(i + 1),
    permCode: ns,
    permName: GROUP_LABELS[ns] ?? '其它',
    parentId: null,
    children,
  }))
})

/** 全部真实权限 id,用于"全选" */
const allPermIds = computed(() => permissions.value.map((p) => p.id))

// ===========================================================================
// 权限分配抽屉
// ===========================================================================
const drawerVisible = ref(false)
const drawerSubmitting = ref(false)
const currentRole = ref<Role | null>(null)
const treeRef = ref<TreeInstance>()

/** 是否已经读回该角色的权限。false 时树不渲染,避免空树先闪一下 */
const permLoaded = ref(false)

/**
 * 树控件的默认勾选值(真实权限 id)。
 * 只在树创建时生效,所以配合下面的 v-if 让树在数据到位后才挂载。
 */
const defaultChecked = ref<number[]>([])

async function openPermissionDrawer(role: Role) {
  currentRole.value = role
  defaultChecked.value = []
  permLoaded.value = false
  drawerVisible.value = true

  try {
    const ids = await listRolePermissions(role.id)
    /*
     * 与权限字典求交集再勾:后端理论上只回真实 id,但万一种子数据或角色关联
     * 里留了字典中已不存在的 id,直接塞给树控件会得到一个"看不见的勾选" ——
     * 保存时它又会被 getCheckedKeys 带回来,管理员看到的和提交的对不上。
     * 过滤掉之后,界面所见即提交所得。
     */
    defaultChecked.value = ids.filter((id) => realPermIds.value.has(id))
    permLoaded.value = true
    // 等 v-if 的树挂载出来,再让它按 defaultChecked 自己勾上
    await nextTick()
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '读取角色权限失败')
    drawerVisible.value = false
  }
}

async function submitPermissions() {
  const role = currentRole.value
  if (!role || !treeRef.value) return

  // 勾选态需要合并「全选」与「半选」:半选的父节点也是被引用的,要一起存下来。
  // 再滤掉分组节点 —— 它们的 id 是前端编的(负数),发过去只会污染数据。
  const checked = treeRef.value.getCheckedKeys(false) as number[]
  const halfChecked = treeRef.value.getHalfCheckedKeys() as number[]
  const permissionIds = [...checked, ...halfChecked].filter((id) => realPermIds.value.has(id))

  // 保存仍是覆盖式的,只是现在读得到原值了,所以只有"清空"这一种情况值得拦一下:
  // 全不勾通常是误操作(比如点了「清空」按钮后忘了重选),后果又是该角色所有人立即失去操作能力
  if (permissionIds.length === 0) {
    try {
      await ElMessageBox.confirm(
        `「${role.roleName}」的全部权限将被移除,该角色的用户会立刻失去所有操作能力。确定继续吗?`,
        '清空权限',
        { confirmButtonText: '确定清空', cancelButtonText: '取消', type: 'warning' },
      )
    } catch {
      return
    }
  }

  drawerSubmitting.value = true
  try {
    await assignPermissions(role.id, permissionIds)
    ElMessage.success(`「${role.roleName}」的权限已更新`)
    drawerVisible.value = false
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '权限保存失败')
  } finally {
    drawerSubmitting.value = false
  }
}

function toggleAll(checked: boolean) {
  if (!treeRef.value) return
  treeRef.value.setCheckedKeys(checked ? allPermIds.value : [])
}

onMounted(load)
</script>

<template>
  <div v-loading="loading" class="roles">
    <PageHeader
      eyebrow="System"
      title="角色权限"
      description="角色是权限的集合。用户的最终权限 = 其所有角色权限的并集。"
    />

    <!-- 权限读写已经配对了,这里不再需要"看不到原有权限"的告警 -->
    <!-- ============ 角色卡片 ============ -->
    <section class="role-grid">
      <article v-for="role in roles" :key="role.id" class="role-card wo-card">
        <header class="role-card__head">
          <div class="role-card__title">
            <h2>{{ role.roleName }}</h2>
            <code class="role-card__code">{{ role.roleCode }}</code>
          </div>
        </header>

        <p class="role-card__remark">{{ role.remark ?? '暂无说明' }}</p>

        <footer class="role-card__foot">
          <el-button text type="primary" @click="openPermissionDrawer(role)">配置权限</el-button>
        </footer>
      </article>
    </section>

    <!-- ============ 权限分配抽屉 ============ -->
    <el-drawer v-model="drawerVisible" size="440px" :title="`配置权限 · ${currentRole?.roleName ?? ''}`">
      <div v-loading="!permLoaded" class="drawer">
        <div class="drawer__bar">
          <span class="wo-text-3">
            勾选该角色可执行的操作,共
            <b class="wo-num">{{ totalPermCount }}</b> 项
          </span>
          <div class="drawer__quick">
            <el-button text size="small" :disabled="!permLoaded" @click="toggleAll(true)">全选</el-button>
            <el-button text size="small" :disabled="!permLoaded" @click="toggleAll(false)">清空</el-button>
          </div>
        </div>

        <!--
          树等权限读回来再挂载:default-checked-keys 只在树创建时生效,
          先渲染空树再改这个值是不会重新勾上的。
          :key 用角色 id —— 不关抽屉直接切角色时,强制重建才不会继承上一个角色的勾选。
        -->
        <el-tree
          v-if="permLoaded"
          ref="treeRef"
          :key="currentRole?.id ?? 0"
          :data="groupedTree"
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
              <!-- 只有真实权限显示权限码;分组节点的 permCode 是命名空间,显示出来反而误导 -->
              <code v-if="data.children.length === 0" class="tree-node__code">{{ data.permCode }}</code>
            </span>
          </template>
        </el-tree>
      </div>

      <template #footer>
        <div class="drawer__foot">
          <el-button @click="drawerVisible = false">取消</el-button>
          <el-button
            type="primary"
            :loading="drawerSubmitting"
            :disabled="!permLoaded"
            @click="submitPermissions"
          >
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

.role-card__remark {
  font-size: 12.5px;
  line-height: 1.65;
  color: var(--wo-ink-3);
  margin-bottom: 16px;
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
