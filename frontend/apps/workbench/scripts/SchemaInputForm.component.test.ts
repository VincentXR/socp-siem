import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import SchemaInputForm from '../src/components/SchemaInputForm.vue'

describe('schema input draft preservation', () => {
  it('retains invalid nested JSON and unknown fields while validating corrections', async () => {
    const wrapper = mount(SchemaInputForm, { props: {
      schema: { properties: { headers: { type: 'object' } } },
      modelValue: { headers: { existing: true }, futureOption: { preserve: 42 } },
      'onUpdate:modelValue': value => wrapper.setProps({ modelValue: value }),
    } })
    const nested = wrapper.findAll('textarea')[0]
    await nested.setValue('{"incomplete":')
    expect((nested.element as HTMLTextAreaElement).value).toBe('{"incomplete":')
    expect(wrapper.emitted('valid')?.at(-1)).toEqual([false])
    expect(wrapper.props('modelValue').headers).toEqual({ existing: true })
    await nested.setValue('{"new":true}')
    expect(wrapper.emitted('valid')?.at(-1)).toEqual([true])
    expect(wrapper.props('modelValue')).toEqual({ headers: { new: true }, futureOption: { preserve: 42 } })
    wrapper.unmount()
  })
})
