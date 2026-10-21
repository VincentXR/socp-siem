type Theme = 'light' | 'dark'
type EChartsRuntime = typeof import('echarts/core')

let activeTheme: Theme = 'light'
let runtimePromise: Promise<EChartsRuntime> | null = null

function registerChartTheme(echarts: EChartsRuntime, theme: Theme): void {
  const dark = theme === 'dark'
  const colors = {
    grid: dark ? 'rgba(255,255,255,.07)' : 'rgba(20,22,26,.06)',
    axis: dark ? '#262b33' : '#e4e3dd',
    label: dark ? '#858b97' : '#6a6c74',
    tooltipBg: dark ? '#16191f' : '#ffffff',
    tooltipText: dark ? '#e8eaee' : '#14161a',
    legend: dark ? '#858b97' : '#6a6c74',
  }
  // 序列色按严重级别语义排布（强调色 → CRITICAL/HIGH/MEDIUM/LOW/INFO），
  // 与 --ns-sev-* 同源，避免图表看起来像另一套系统。
  const series = dark
    ? ['#8fa6ff', '#ff6b76', '#ff9770', '#e0b04f', '#7fa9dd', '#9aa1ad']
    : ['#1e46d6', '#c8102e', '#d9603a', '#c99a3e', '#6f9dd0', '#666b75']
  echarts.registerTheme('socp', {
    color: series,
    backgroundColor: 'transparent',
    textStyle: { color: colors.label, fontFamily: '-apple-system, BlinkMacSystemFont, "PingFang SC", sans-serif' },
    title: { textStyle: { color: colors.label, fontSize: 13, fontWeight: 600 }, subtextStyle: { color: colors.legend, fontSize: 11 } },
    legend: { textStyle: { color: colors.legend, fontSize: 11 } },
    tooltip: {
      backgroundColor: colors.tooltipBg,
      borderColor: colors.grid,
      borderWidth: 1,
      textStyle: { color: colors.tooltipText, fontSize: 12 },
      extraCssText: 'border-radius:10px;box-shadow:0 8px 24px rgba(0,0,0,.12);',
    },
    categoryAxis: {
      axisLine: { lineStyle: { color: colors.axis } },
      axisTick: { show: false },
      axisLabel: { color: colors.label, fontSize: 11 },
      splitLine: { show: false },
    },
    valueAxis: {
      axisLine: { show: false },
      axisTick: { show: false },
      axisLabel: { color: colors.label, fontSize: 11 },
      splitLine: { lineStyle: { color: colors.grid, type: 'dashed' } },
    },
    line: { smooth: true, symbol: 'circle', symbolSize: 6, lineStyle: { width: 2 } },
    bar: { itemStyle: { borderRadius: [6, 6, 0, 0] } },
  })
}

export function setChartTheme(theme: Theme): void {
  activeTheme = theme
  if (runtimePromise) void runtimePromise.then(runtime => registerChartTheme(runtime, theme)).catch(() => undefined)
}

export function loadEcharts(): Promise<EChartsRuntime> {
  if (!runtimePromise) {
    runtimePromise = import('./echarts-runtime').then(({ default: echarts }) => {
      registerChartTheme(echarts, activeTheme)
      return echarts
    }).catch(error => {
      runtimePromise = null
      throw error
    })
  }
  return runtimePromise
}
