import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 自托管字体:Plus Jakarta Sans 负责拉丁字母与数字,中文由系统黑体兜底
import '@fontsource-variable/plus-jakarta-sans/wght.css'

import App from './App.vue'
import router from './router'
import './styles/index.scss'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
