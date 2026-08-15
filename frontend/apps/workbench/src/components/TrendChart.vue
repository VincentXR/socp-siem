<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import type { ECharts } from 'echarts/core'
import { loadEcharts } from '../lib/echarts'

/**
 * 近 7 日告警趋势折线图（概览页/态势页共用）。
 * props.data: { "2026-08-03": 0, ... }（日期 → 条数）
 * 内置 socp echarts 主题 + 深浅色自适应 + 峰值标点 + tooltip 明细。
 */
const props = withDefaults(defineProps<{ data?: Record<string, number>; variant?: 'overview' | 'situation' }>(), {
  variant: 'overview',
})

const el = ref<HTMLElement>()
let chart: ECharts | null = null
let renderToken = 0

function themeColor(key: string): string {
  const dark = document.documentElement.classList.contains('dark')
  const map: Record<string, string> = {
    label: dark ? '#e6edf3' : '#1f2328',
    axis: dark ? '#8b949e' : '#57606a',
  }
  return map[key] ?? '#57606a'
}

async function render() {
  const d = props.data
  if (!el.value || !d) return
  const token = ++renderToken
  const echarts = await loadEcharts()
  if (token !== renderToken || !el.value) return
  if (!chart || chart.isDisposed()) chart = echarts.init(el.value, 'socp')
  const dark = document.documentElement.classList.contains('dark')
  const days = Object.keys(d).sort()
  const vals = days.map((k) => d[k])
  if (props.variant === 'situation') {
    // 态势页：带坐标轴、蓝色实线（与旧 sitTrend 图表一致）
    chart.setOption({
      grid: { left: 36, right: 12, top: 22, bottom: 24 }, tooltip: { trigger: 'axis' },
      xAxis: { type: 'category', data: days.map((k) => k.slice(5)), axisLabel: { fontSize: 10 } },
      yAxis: { type: 'value', axisLabel: { fontSize: 10 } },
      series: [{
        type: 'line', smooth: true, showSymbol: false, data: vals,
        lineStyle: { color: '#409eff', width: 2 },
        areaStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [{ offset: 0, color: 'rgba(64,158,255,.35)' }, { offset: 1, color: 'rgba(64,158,255,.02)' }]) },
      }],
    }, true)
    return
  }
  chart.setOption({
    grid: { left: 10, right: 10, top: 24, bottom: 6, containLabel: false },
    xAxis: {
      type: 'category', data: days,
      axisLabel: { fontSize: 10.5, color: themeColor('label'), formatter: (v: string) => v.slice(5) },
      axisLine: { lineStyle: { color: themeColor('axis') } },
      axisTick: { show: false },
    },
    yAxis: { type: 'value', show: false, minInterval: 1 },
    tooltip: {
      trigger: 'axis',
      formatter: (ps: Array<{ axisValue: string; value: number }>) => {
        const p = ps[0]
        return `${p.axisValue}<br/><b>${p.value}</b> 条告警`
      },
    },
    series: [{
      type: 'line', smooth: true, symbol: 'circle', symbolSize: 6,
      data: vals,
      lineStyle: { color: themeColor('axis'), width: 0 },
      itemStyle: { color: themeColor('label') },
      label: {
        show: true, position: 'top', fontSize: 10.5, color: themeColor('label'),
        formatter: (p: { value: number }) => (p.value > 0 ? String(p.value) : ''),
      },
      areaStyle: {
        color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
          { offset: 0, color: dark ? 'rgba(68,147,248,.4)' : 'rgba(9,105,218,.32)' },
          { offset: 1, color: 'rgba(0,0,0,0)' },
        ]),
      },
      markPoint: {
        data: [{ type: 'max', name: '峰值' }],
        symbolSize: 44, label: { fontSize: 10, color: '#fff', formatter: '{c}' },
        itemStyle: { color: dark ? '#3fb950' : '#1a7f37' },
      },
    }],
  }, true)
}

onMounted(() => {
  void render()
  // 主题切换时重绘（监听 html.dark class）
  const mo = new MutationObserver(() => { void render() })
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  ;(el.value as any)._mo = mo
})

watch(() => props.data, () => { void render() }, { deep: true })

onBeforeUnmount(() => {
  renderToken++
  ;(el.value as any)?._mo?.disconnect?.()
  chart?.dispose()
  chart = null
})
</script>

<template>
  <div ref="el" class="trend-chart" style="width: 100%; height: 100%"></div>
</template>
