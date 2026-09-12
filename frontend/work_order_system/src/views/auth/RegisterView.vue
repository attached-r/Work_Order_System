<script setup lang="ts">
/**
 * 注册页
 *
 * 与登录页共用同一套氛围,但收成单栏居中 —— 注册是低频动作,不需要品牌说服力,
 * 只需要把表单填完。注册成功后不自动登录,跳回登录页让用户显式登一次。
 */
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'

const router = useRouter()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

/**
 * 没有「所属部门」这一项:两个部门查询接口(/department/list 与
 * /department/directory)都要求**已登录**,而注册时用户还没有 token,都拿不到。
 * 部门由管理员在用户管理里分配 —— 反正新账号也没有角色,本来就要等管理员配完才能用。
 */
const form = reactive({
  username: '',
  realName: '',
  password: '',
  confirmPassword: '',
  phone: '',
})

/** 两次密码一致性校验 */
function validateConfirm(_rule: unknown, value: string, callback: (error?: Error) => void) {
  if (value !== form.password) callback(new Error('两次输入的密码不一致'))
  else callback()
}

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3-32 个字符', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z0-9_]+$/,
      message: '只能包含字母、数字和下划线',
      trigger: 'blur',
    },
  ],
  realName: [
    { required: true, message: '请输入真实姓名', trigger: 'blur' },
    { max: 32, message: '姓名长度不能超过 32 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 个字符', trigger: 'blur' },
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' },
  ],
  phone: [{ pattern: /^1[3-9]\d{9}$/, message: '请输入有效的手机号', trigger: 'blur' }],
}

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.register({
      username: form.username,
      password: form.password,
      confirmPassword: form.confirmPassword,
      realName: form.realName,
      // 空串会被请求层丢掉,后端收到的就是不传
      phone: form.phone,
    })
    ElMessage.success('注册成功,请登录')
    router.push({ name: 'login' })
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '注册失败,请稍后重试')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="register">
    <div class="register__glow" aria-hidden="true" />
    <div class="register__grid" aria-hidden="true" />

    <div class="register__card wo-card">
      <RouterLink to="/login" class="register__brand">
        <span class="register__mark">
          <svg viewBox="0 0 24 24" width="20" height="20" fill="none">
            <rect x="2.5" y="2.5" width="19" height="19" rx="5.5" stroke="currentColor" stroke-width="1.9" />
            <path
              d="M7.5 12.2l3 3 6-6.4"
              stroke="currentColor"
              stroke-width="2.1"
              stroke-linecap="round"
              stroke-linejoin="round"
            />
          </svg>
        </span>
        <span>智能工单系统</span>
      </RouterLink>

      <header class="register__head">
        <p class="wo-eyebrow">Create account</p>
        <h2>注册新账号</h2>
        <p class="wo-text-3">注册后由管理员分配角色、权限与所属部门</p>
      </header>

      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" placeholder="字母、数字、下划线" clearable />
        </el-form-item>

        <el-form-item label="真实姓名" prop="realName">
          <el-input v-model="form.realName" placeholder="用于工单流转中展示" clearable />
        </el-form-item>

        <div class="register__row">
          <el-form-item label="密码" prop="password">
            <el-input v-model="form.password" type="password" placeholder="至少 6 位" show-password />
          </el-form-item>

          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="再输一次" show-password />
          </el-form-item>
        </div>

        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="选填" clearable />
        </el-form-item>

        <el-button type="primary" class="register__submit" :loading="loading" @click="handleSubmit">
          注 册
        </el-button>
      </el-form>

      <p class="register__switch">
        已经有账号了?
        <RouterLink to="/login">返回登录</RouterLink>
      </p>
    </div>
  </div>
</template>

<style scoped lang="scss">
.register {
  position: relative;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(24px, 5vh, 56px) 20px;
  overflow: hidden;
  background: linear-gradient(165deg, #ffffff 0%, #f2f7fe 55%, #e8f0fc 100%);
}

.register__glow {
  position: absolute;
  width: 560px;
  height: 560px;
  top: -240px;
  right: -180px;
  border-radius: 50%;
  filter: blur(80px);
  background: radial-gradient(circle, rgba(47, 111, 237, 0.2), transparent 70%);
  pointer-events: none;
}

.register__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(47, 111, 237, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 111, 237, 0.05) 1px, transparent 1px);
  background-size: 34px 34px;
  mask-image: radial-gradient(circle at 50% 30%, #000 0%, transparent 72%);
}

.register__card {
  position: relative;
  z-index: 2;
  width: 100%;
  max-width: 520px;
  padding: clamp(24px, 3.4vw, 38px);
  border-radius: var(--wo-radius-lg);
  box-shadow: var(--wo-shadow-md);
}

.register__brand {
  display: inline-flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 22px;
  font-size: 14px;
  font-weight: 700;
  color: var(--wo-ink-1);

  &:hover {
    color: var(--wo-ink-1);
  }
}

.register__mark {
  width: 32px;
  height: 32px;
  display: grid;
  place-items: center;
  border-radius: 9px;
  color: #fff;
  background: linear-gradient(145deg, #4c85f5 0%, var(--wo-brand) 55%, #1e56cf 100%);
  box-shadow: 0 2px 6px rgba(47, 111, 237, 0.28);
}

.register__head {
  margin-bottom: 24px;

  h2 {
    font-size: 22px;
    font-weight: 700;
    letter-spacing: -0.02em;
    margin: 6px 0 4px;
  }
}

// 两列并排的表单行;窄屏自动落回单列
.register__row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 0 16px;
}

.register__submit {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.08em;
  margin-top: 6px;
}

.register__switch {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: var(--wo-ink-3);
}

@media (max-width: 560px) {
  .register__row {
    grid-template-columns: 1fr;
  }
}
</style>
