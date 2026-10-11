import { readFileSync } from 'node:fs'
import { afterEach, describe, expect, it } from 'vitest'

const stylesheet = readFileSync('src/styles.css', 'utf8')
const mounted: HTMLElement[] = []
function control(className: string) {
  const element = document.createElement('div')
  element.className = className
  document.body.append(element)
  mounted.push(element)
  return getComputedStyle(element)
}
afterEach(() => {
  for (const element of mounted.splice(0)) element.remove()
})

describe('workbench compact control cascade', () => {
  it('retains the resolved button geometry and primary emphasis after removing superseded declarations', () => {
    const style = document.createElement('style')
    style.textContent = stylesheet
    document.head.append(style)
    mounted.push(style)
    for (const className of ['el-button', 'el-button el-button--primary', 'el-button is-round', 'el-button is-link']) {
      const computed = control(className)
      expect(computed.paddingTop).toBe('6px')
      expect(computed.paddingBottom).toBe('6px')
      expect(computed.paddingLeft).toBe('11px')
      expect(computed.paddingRight).toBe('11px')
      expect(computed.borderRadius).toBe('6px')
      expect(computed.minHeight).toBe('30px')
    }
    expect(control('el-button').fontWeight).toBe('500')
    expect(control('el-button el-button--primary').fontWeight).toBe('600')
  })

  it('uses the same resolved radius for inputs, selects and textareas', () => {
    const style = document.createElement('style')
    style.textContent = stylesheet
    document.head.append(style)
    mounted.push(style)
    for (const className of ['el-input__wrapper', 'el-select__wrapper', 'el-textarea__inner']) {
      expect(control(className).borderRadius).toBe('6px')
    }
  })
})
