import * as echarts from 'echarts/core'
import { BarChart, GaugeChart, LineChart, PieChart } from 'echarts/charts'
import {
  GridComponent,
  LegendComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

echarts.use([
  BarChart,
  GaugeChart,
  LineChart,
  PieChart,
  GridComponent,
  LegendComponent,
  MarkPointComponent,
  TitleComponent,
  TooltipComponent,
  CanvasRenderer,
])

export { echarts }

export function registerChartTheme(theme: 'light' | 'dark'): void {
  const dark = theme === 'dark'
  const colors = {
    grid: dark ? 'rgba(255,255,255,.06)' : 'rgba(31,35,40,.06)',
    axis: dark ? '#3d444d' : '#d1d9e0',
    label: dark ? '#9198a1' : '#59636e',
    tooltipBg: dark ? '#21262d' : '#ffffff',
    tooltipText: dark ? '#e6edf3' : '#1f2328',
    legend: dark ? '#9198a1' : '#59636e',
  }
  echarts.registerTheme('socp', {
    color: ['#4493f8', '#0969da', '#30d158', '#ff9f0a', '#f85149', '#8250df', '#39c5cf', '#bf8700'],
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
