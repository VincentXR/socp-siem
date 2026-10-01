<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted } from 'vue'

const props = withDefaults(defineProps<{ value: number; duration?: number }>(), {
  value: 0,
  duration: 700,
})

const shown = ref(0)
let raf = 0

const motionPreference = typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  ? window.matchMedia('(prefers-reduced-motion: reduce)')
  : undefined

function run() {
  cancelAnimationFrame(raf)
  if (motionPreference?.matches || props.duration <= 0) { shown.value = props.value; return }
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
onMounted(() => {
  motionPreference?.addEventListener?.('change', run)
  run()
})
onUnmounted(() => {
  cancelAnimationFrame(raf)
  motionPreference?.removeEventListener?.('change', run)
})
watch(() => props.value, run)
</script>

<template>
  <span class="kpi-anim">{{ shown }}</span>
</template>
