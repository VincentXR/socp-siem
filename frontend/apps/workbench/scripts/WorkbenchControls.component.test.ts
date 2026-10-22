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

describe('workbench comfortable control cascade', () => {
  it('keeps regular actions comfortable and table actions compact without losing primary emphasis', () => {
    const style = document.createElement('style')
    style.textContent = stylesheet
    document.head.append(style)
    mounted.push(style)
    for (const className of ['el-button', 'el-button el-button--primary', 'el-button is-round']) {
      const computed = control(className)
      expect(computed.paddingTop).toBe('7px')
      expect(computed.paddingBottom).toBe('7px')
      expect(computed.paddingLeft).toBe('12px')
      expect(computed.paddingRight).toBe('12px')
      expect(computed.borderRadius).toBe('6px')
      expect(computed.minHeight).toBe('32px')
    }
    const compact = control('el-button el-button--small')
    expect(compact.minHeight).toBe('28px')
    expect(compact.paddingTop).toBe('5px')
    expect(compact.paddingLeft).toBe('10px')
    const link = control('el-button el-button--small is-link')
    expect(link.minHeight).toBe('28px')
    expect(link.paddingTop).toBe('4px')
    expect(link.paddingLeft).toBe('6px')
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
