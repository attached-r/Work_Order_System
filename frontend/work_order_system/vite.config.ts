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
      '/api': {
        target: 'http://localhost:10001',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
})
