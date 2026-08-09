<script setup lang="ts">
import { ref, watch, onMounted } from 'vue'

const props = withDefaults(defineProps<{ value: number; duration?: number }>(), {
  value: 0,
  duration: 700,
})

const shown = ref(0)
let raf = 0

function run() {
  cancelAnimationFrame(raf)
  const from = shown.value
  const to = props.value
  const start = performance.now()
  function tick(now: number) {
    const p = Math.min((now - start) / props.duration, 1)
    const eased = 1 - Math.pow(1 - p, 3)
    shown.value = Math.round(from + (to - from) * eased)
    if (p < 1) raf = requestAnimationFrame(tick)
    else shown.value = to
  }
  raf = requestAnimationFrame(tick)
}
onMounted(run)
watch(() => props.value, run)
</script>

<template>
  <span class="kpi-anim">{{ shown }}</span>
</template>
