<script setup lang="ts">
import brandMarkUrl from '../assets/brand-mark.svg'
</script>

<template>
  <img class="socp-brand-mark" :src="brandMarkUrl" width="32" height="32" alt="" aria-hidden="true" draggable="false" />
</template>

<style scoped>
.socp-brand-mark { display: block; flex: none; user-select: none; }
</style>
