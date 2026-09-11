<script setup lang="ts">
// 全局注入中文语言包,否则分页/日期选择器/确认框都是英文
import zhCn from 'element-plus/es/locale/lang/zh-cn'
</script>

<template>
  <el-config-provider :locale="zhCn">
    <RouterView />
  </el-config-provider>
</template>
