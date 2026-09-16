import { createApp } from 'vue'
import { createPinia } from 'pinia'

// 自托管字体:Plus Jakarta Sans 负责拉丁字母与数字,中文由系统黑体兜底
import '@fontsource-variable/plus-jakarta-sans/wght.css'

import App from './App.vue'
import router from './router'

// ---------------------------------------------------------------------------
// 服务式组件的样式补丁
//
// ElMessage / ElMessageBox 是「函数式」调用的 —— 直接 import 函数然后 .confirm()/.success(),
// 调用点不在模板里,unplugin 的 resolver 就抓不到,也就不会按需注入样式。
// 少了这两行,确认框会退化成一坨裸 HTML 贴在遮罩左上角(.el-overlay 的定位来自
// el-dialog 顺带引入的 overlay.scss,所以它还是浮在页面上,只是壳没了)。
//
// 必须放在 styles/index.scss 之前:EP 先落地,我们的覆写才能盖住默认值。
// ---------------------------------------------------------------------------
import 'element-plus/es/components/message/style/index'
import 'element-plus/es/components/message-box/style/index'

import './styles/index.scss'

const app = createApp(App)

app.use(createPinia())
app.use(router)

app.mount('#app')
