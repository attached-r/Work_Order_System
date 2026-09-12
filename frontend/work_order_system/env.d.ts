/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * 接口根地址。开发环境留空即可 —— 默认 '/api',由 vite 代理转发到后端。
   * 生产环境在部署时注入真实网关地址,例如 https://wo.example.com/api
   */
  readonly VITE_API_BASE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
