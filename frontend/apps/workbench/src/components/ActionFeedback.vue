<script setup lang="ts">
import ElAlert from 'element-plus/es/components/alert/index.mjs'
import 'element-plus/es/components/alert/style/css.mjs'
defineProps<{ error?: string }>()
</script>
<template><el-alert v-if="error" :title="error" type="error" :closable="false" show-icon role="alert" class="action-feedback" /></template>
