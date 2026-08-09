// SOC 共享 UI 组件包（P16）：告警级别徽标等基础组件
import { defineComponent, h } from 'vue'

const COLORS: Record<string, string> = {
  CRITICAL: '#f56c6c',
  HIGH: '#e63946',
  MEDIUM: '#e6a23c',
  LOW: '#909399',
  INFO: '#909399',
}

/** 告警严重级别徽标（内联样式，不依赖 Element Plus 全局类） */
export const SeverityTag = defineComponent({
  name: 'SeverityTag',
  props: {
    severity: { type: String, required: true },
  },
  setup(props) {
    return () => {
      const key = (props.severity || 'INFO').toUpperCase()
      const color = COLORS[key] ?? COLORS.INFO
      return h(
        'span',
        {
          style: {
            color: '#fff',
            background: color,
            borderRadius: '4px',
            padding: '0 8px',
            fontSize: '12px',
            lineHeight: '20px',
            display: 'inline-block',
            fontWeight: 600,
          },
        },
        key,
      )
    }
  },
})

export const version = '1.0.0'
export default { SeverityTag, version }
