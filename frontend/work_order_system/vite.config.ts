import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'
import AutoImport from 'unplugin-auto-import/vite'
import Components from 'unplugin-vue-components/vite'
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers'

/** 主题变量文件:它自己不能被注入,否则会自引用循环 */
const THEME_ENTRY = '/src/styles/element/index.scss'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),

    // 自动引入 vue / vue-router / pinia 的组合式 API
    AutoImport({
      imports: ['vue', 'vue-router', 'pinia'],
      resolvers: [ElementPlusResolver({ importStyle: 'sass' })],
      // 生成物必须落在 src/ 下:tsconfig.app.json 的 include 只覆盖 env.d.ts 与 src/**
      dts: 'src/types/auto-imports.d.ts',
    }),

    // 按需注册 Element Plus 组件,importStyle: 'sass' 才能吃到我们的主题变量
    Components({
      resolvers: [ElementPlusResolver({ importStyle: 'sass' })],
      dts: 'src/types/components.d.ts',
    }),
  ],

  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },

  css: {
    preprocessorOptions: {
      scss: {
        // 把主题变量注入每个 scss 文件,使 <style lang="scss"> 里也能直接用 EP 变量。
        // 用函数形式是为了能按文件名跳过主题文件本身(字符串形式会造成循环加载)。
        additionalData: (source: string, filename: string) => {
          const id = filename.replace(/\\/g, '/')
          if (id.endsWith(THEME_ENTRY)) return source
          return `@use "@/styles/element/index.scss" as *;\n${source}`
        },
      },
    },
  },

  server: {
    port: 5173,
    // 把 /api 代理到 Spring Boot,借同源转发绕开后端未配置的 CORS。
    // 后端没有 context-path,所以要把 /api 前缀改写掉:
    // 前端请求 /api/user/login → 实际打到 http://localhost:10001/user/login
    proxy: {
      // 步骤 1:Agent 服务(项目二,10002)必须排在 /api 之前 ——
      // 代理按声明顺序匹配,否则 /api/agent/chat 会被下面的 /api 规则吃掉。
      // 注意这里**不能 rewrite**:Agent 的控制器本身就以 /api/agent 为前缀,
      // 去掉 /api 会变成 /agent/chat 从而 404。
      '/api/agent': {
        target: 'http://localhost:10002',
        changeOrigin: true,
        // SSE 长连接:代理层不要主动超时,否则回答到一半会被掐断。
        proxyTimeout: 0,
        timeout: 0,
      },

      // 步骤 2:项目一(10001)的业务接口,保持原有的去前缀行为。
      '/api': {
        target: 'http://localhost:10001',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
