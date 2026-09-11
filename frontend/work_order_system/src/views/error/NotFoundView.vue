<script setup lang="ts">
/**
 * 404
 *
 * 不用 Element Plus 的 result 组件,自己拼:一个巨大的「404」做视觉主体,
 * 数字用主色描边而不是填充,保持淡色系的克制。
 */
import { useRouter } from 'vue-router'

const router = useRouter()
</script>

<template>
  <div class="nf">
    <div class="nf__glow" aria-hidden="true" />
    <div class="nf__grid" aria-hidden="true" />

    <div class="nf__inner">
      <p class="nf__code wo-num" aria-hidden="true">404</p>

      <h1 class="nf__title">这个页面不存在</h1>
      <p class="nf__desc">
        链接可能已经失效,或者你没有访问它的权限。 如果是权限问题,请联系管理员确认你的角色配置。
      </p>

      <div class="nf__acts">
        <el-button type="primary" @click="router.push({ name: 'dashboard' })">回到工作台</el-button>
        <el-button @click="router.push({ name: 'workorder-list' })">查看工单</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped lang="scss">
.nf {
  position: relative;
  min-height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
  overflow: hidden;
  background: var(--wo-canvas);
}

.nf__glow {
  position: absolute;
  width: 520px;
  height: 520px;
  top: -180px;
  left: 50%;
  transform: translateX(-50%);
  border-radius: 50%;
  filter: blur(90px);
  background: radial-gradient(circle, rgba(47, 111, 237, 0.16), transparent 70%);
  pointer-events: none;
}

.nf__grid {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    linear-gradient(rgba(47, 111, 237, 0.045) 1px, transparent 1px),
    linear-gradient(90deg, rgba(47, 111, 237, 0.045) 1px, transparent 1px);
  background-size: 34px 34px;
  mask-image: radial-gradient(circle at 50% 45%, #000 0%, transparent 70%);
}

.nf__inner {
  position: relative;
  z-index: 2;
  text-align: center;
  max-width: 460px;
}

// 数字做视觉主体:描边空心,和淡色系保持一致
.nf__code {
  font-size: clamp(88px, 16vw, 148px);
  font-weight: 800;
  line-height: 1;
  letter-spacing: -0.05em;
  color: transparent;
  -webkit-text-stroke: 2px var(--wo-hairline-brand);
  margin-bottom: 8px;
  user-select: none;
}

.nf__title {
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  color: var(--wo-ink-1);
  margin-bottom: 10px;
}

.nf__desc {
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--wo-ink-3);
  margin-bottom: 26px;
}

.nf__acts {
  display: flex;
  justify-content: center;
  gap: 10px;
  flex-wrap: wrap;
}
</style>
