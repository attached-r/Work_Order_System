<script setup lang="ts">
/**
 * 登录页
 *
 * 左品牌 / 右表单的分栏。左栏用渐变网格 + 蓝图网格 + 玻璃卡片铺一层"氛围",
 * 右栏只放表单,保持极简。窄屏时左栏整体隐藏,表单占满。
 *
 * 演示账号做成可点击的快填,省去手输 —— 评审和演示时很省事。
 */
import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance, FormRules } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { DEMO_ACCOUNTS, DEMO_PASSWORD } from '@/constants/demo'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref<FormInstance>()
const loading = ref(false)

const form = reactive({
  username: '',
  password: '',
})

const rules: FormRules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 32, message: '用户名长度为 3-32 个字符', trigger: 'blur' },
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 32, message: '密码长度为 6-32 个字符', trigger: 'blur' },
  ],
}

/** 登录成功后回到来源页;没有来源页则进工作台 */
const redirectTarget = computed(() => {
  const r = route.query.redirect
  return typeof r === 'string' && r ? r : '/dashboard'
})

async function handleSubmit() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    await userStore.login(form.username, form.password)
    ElMessage.success(`欢迎回来,${userStore.displayName}`)
    router.push(redirectTarget.value)
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '登录失败,请稍后重试')
  } finally {
    loading.value = false
  }
}

/** 一键填入演示账号 */
function fillAccount(username: string) {
  form.username = username
  form.password = DEMO_PASSWORD
}
</script>

<template>
  <div class="auth">
    <!-- ============ 左:品牌区 ============ -->
    <section class="auth__brand">
      <div class="auth__brand-inner">
        <div class="auth__logo">
          <span class="auth__mark">
            <svg viewBox="0 0 24 24" width="24" height="24" fill="none">
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
          <span class="auth__wordmark">智能工单系统</span>
        </div>

        <h1 class="auth__headline">
          让每一次<br />
          <em>问题的流转</em><br />
          都有迹可循
        </h1>

        <p class="auth__sub">
          从提单、审核、派单到处理与验收,全流程留痕。 RBAC 权限模型保证每个人只看到该看到的工单。
        </p>

        <!-- 流程条:把状态机可视化成一条线,顺便解释了产品在做什么 -->
        <ol class="flow">
          <li v-for="(step, i) in ['提交', '审核', '派单', '处理', '验收']" :key="step">
            <span class="flow__idx">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="flow__label">{{ step }}</span>
          </li>
        </ol>
      </div>

      <!-- 背景装饰:两团极淡的光斑 + 蓝图网格 -->
      <div class="auth__glow auth__glow--a" aria-hidden="true" />
      <div class="auth__glow auth__glow--b" aria-hidden="true" />
      <div class="auth__grid" aria-hidden="true" />
    </section>

    <!-- ============ 右:表单区 ============ -->
    <section class="auth__panel">
      <div class="auth__form-wrap">
        <header class="auth__form-head">
          <p class="wo-eyebrow">Sign in</p>
          <h2>登录</h2>
          <p class="wo-text-3">使用你的账号继续</p>
        </header>

        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          size="large"
          label-position="top"
          @submit.prevent="handleSubmit"
        >
          <el-form-item label="用户名" prop="username">
            <el-input v-model="form.username" placeholder="请输入用户名" autocomplete="username" clearable />
          </el-form-item>

          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              type="password"
              placeholder="请输入密码"
              autocomplete="current-password"
              show-password
              @keyup.enter="handleSubmit"
            />
          </el-form-item>

          <el-button type="primary" class="auth__submit" :loading="loading" @click="handleSubmit">
            登 录
          </el-button>
        </el-form>

        <p class="auth__switch">
          还没有账号?
          <RouterLink to="/register">立即注册</RouterLink>
        </p>

        <!-- 演示账号快填 -->
        <div class="demo">
          <p class="wo-eyebrow demo__title">演示账号 · 口令均为 {{ DEMO_PASSWORD }}</p>
          <div class="demo__list">
            <button
              v-for="acc in DEMO_ACCOUNTS"
              :key="acc.username"
              type="button"
              class="demo__item"
              :class="{ 'is-active': form.username === acc.username }"
              @click="fillAccount(acc.username)"
            >
              <b>{{ acc.username }}</b>
              <i>{{ acc.roleName }}</i>
            </button>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<style scoped lang="scss">
.auth {
  display: grid;
  grid-template-columns: 1.05fr 1fr;
  height: 100%;
  background: var(--wo-surface);
}

// ===========================================================================
// 左:品牌区
// ===========================================================================
.auth__brand {
  position: relative;
  overflow: hidden;
  display: flex;
  align-items: center;
  // 蓝白渐变:从纯白过渡到极淡的蓝
  background: linear-gradient(150deg, #ffffff 0%, #f2f7fe 42%, #e6effc 100%);
  border-right: 1px solid var(--wo-hairline);
}

.auth__brand-inner {
  position: relative;
  z-index: 2;
  padding: clamp(32px, 6vw, 72px);
  max-width: 620px;
}

.auth__logo {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: clamp(36px, 7vh, 72px);
}

.auth__mark {
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 11px;
  color: #fff;
  background: linear-gradient(145deg, #4c85f5 0%, var(--wo-brand) 55%, #1e56cf 100%);
  box-shadow: 0 4px 14px rgba(47, 111, 237, 0.3);
}

.auth__wordmark {
  font-size: 16px;
  font-weight: 700;
  color: var(--wo-ink-1);
  letter-spacing: -0.01em;
}

.auth__headline {
  font-size: clamp(30px, 3.6vw, 46px);
  font-weight: 700;
  line-height: 1.22;
  letter-spacing: -0.03em;
  color: var(--wo-ink-1);

  // 中间那行用主色 + 手写感的下划线,是整页唯一的"抒情"笔触
  em {
    font-style: normal;
    color: var(--wo-brand);
    position: relative;

    &::after {
      content: '';
      position: absolute;
      left: 0;
      right: 0;
      bottom: 0.06em;
      height: 0.32em;
      background: var(--wo-brand-wash-2);
      border-radius: 2px;
      z-index: -1;
    }
  }
}

.auth__sub {
  margin-top: 20px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--wo-ink-2);
  max-width: 46ch;
}

// ---- 流程条 ----
.flow {
  list-style: none;
  margin: clamp(36px, 7vh, 64px) 0 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 10px 0;

  li {
    display: flex;
    align-items: baseline;
    gap: 7px;
    padding-right: 18px;
    margin-right: 18px;
    position: relative;

    // 步与步之间的连接线
    &:not(:last-child)::after {
      content: '';
      position: absolute;
      right: 0;
      top: 50%;
      width: 18px;
      height: 1px;
      background: var(--wo-hairline-strong);
    }
  }
}

.flow__idx {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.08em;
  color: var(--wo-brand);
  font-variant-numeric: tabular-nums;
}

.flow__label {
  font-size: 13px;
  font-weight: 500;
  color: var(--wo-ink-2);
}

// ---- 背景装饰 ----
.auth__glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  pointer-events: none;
}

.auth__glow--a {
  width: 460px;
  height: 460px;
  right: -160px;
  top: -140px;
  background: radial-gradient(circle, rgba(47, 111, 237, 0.22), transparent 70%);
}

.auth__glow--b {
  width: 380px;
  height: 380px;
  left: -140px;
  bottom: -130px;
  background: radial-gradient(circle, rgba(124, 168, 247, 0.28), transparent 70%);
}

// 蓝图网格:登录页的"图纸"暗示,和工单系统的工程气质呼应
.auth__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(47, 111, 237, 0.055) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 111, 237, 0.055) 1px, transparent 1px);
  background-size: 34px 34px;
  // 向中心渐隐,避免网格铺满显得脏
  mask-image: radial-gradient(circle at 30% 40%, #000 0%, transparent 78%);
}

// ===========================================================================
// 右:表单区
// ===========================================================================
.auth__panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(24px, 4vw, 48px);
  overflow-y: auto;
}

.auth__form-wrap {
  width: 100%;
  max-width: 380px;
}

.auth__form-head {
  margin-bottom: 28px;

  h2 {
    font-size: 26px;
    font-weight: 700;
    letter-spacing: -0.02em;
    margin: 8px 0 4px;
  }
}

.auth__submit {
  width: 100%;
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 0.08em;
  margin-top: 4px;
}

.auth__switch {
  margin-top: 18px;
  text-align: center;
  font-size: 13px;
  color: var(--wo-ink-3);
}

// ---- 演示账号 ----
.demo {
  margin-top: 32px;
  padding-top: 20px;
  border-top: 1px dashed var(--wo-hairline-strong);
}

.demo__title {
  margin-bottom: 10px;
}

.demo__list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 6px;
}

.demo__item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 7px 10px;
  border: 1px solid var(--wo-hairline);
  border-radius: var(--wo-radius-sm);
  background: var(--wo-surface-raised);
  cursor: pointer;
  text-align: left;
  transition: all var(--wo-dur-fast) var(--wo-ease);

  b {
    font-size: 12px;
    font-weight: 600;
    color: var(--wo-ink-1);
    font-family: 'Plus Jakarta Sans Variable', monospace;
  }

  i {
    font-style: normal;
    font-size: 10px;
    color: var(--wo-ink-3);
  }

  &:hover {
    border-color: var(--wo-hairline-brand);
    background: var(--wo-brand-wash);
  }

  &.is-active {
    border-color: var(--wo-brand);
    background: var(--wo-brand-wash);
    box-shadow: 0 0 0 2px var(--wo-brand-ring);
  }
}

// ===========================================================================
// 响应式:窄屏收起品牌区
// ===========================================================================
@media (max-width: 900px) {
  .auth {
    grid-template-columns: 1fr;
  }

  .auth__brand {
    display: none;
  }
}
</style>
