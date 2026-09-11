import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import SchemaInputForm from '../src/components/SchemaInputForm.vue'
import { validateSchemaInput } from '../src/utils/schemaValidation'

describe('schema input draft preservation', () => {
  it('validates the initial value, advanced JSON and schema changes', async () => {
    const wrapper = mount(SchemaInputForm, { props: {
      schema: { type: 'object', required: ['reason', 'count'], properties: { reason: { type: 'string', minLength: 3 }, count: { type: 'integer', minimum: 1, maximum: 5 } } },
      modelValue: {},
      'onUpdate:modelValue': value => wrapper.setProps({ modelValue: value }),
    } })
    expect(wrapper.emitted('valid')?.at(-1)).toEqual([false])
    const raw = wrapper.find('details textarea')
    await raw.setValue('{"reason":"ok","count":9}')
    expect(wrapper.emitted('valid')?.at(-1)).toEqual([false])
    await raw.setValue('{"reason":"reviewed","count":2}')
    expect(wrapper.emitted('valid')?.at(-1)).toEqual([true])
    await wrapper.setProps({ schema: { required: ['approver'] } })
    expect(wrapper.emitted('valid')?.at(-1)).toEqual([false])
    wrapper.unmount()
  })

  it('validates nested schemas without confusing false, zero and null with absence', () => {
    const schema = { type: 'object', required: ['approved', 'count', 'reason'], additionalProperties: false,
      properties: { approved: { type: 'boolean' }, count: { type: 'integer', minimum: 0 }, reason: { type: ['string', 'null'] },
        entries: { type: 'array', minItems: 1, items: { type: 'object', required: ['choice'], properties: { choice: { enum: ['A', 'B'] } } } } } }
    expect(validateSchemaInput({ approved: false, count: 0, reason: null }, schema)).toEqual([])
    expect(validateSchemaInput({ approved: 'false', count: -1, reason: undefined, extra: 1, entries: [{ choice: 'C' }] }, schema))
      .toEqual(expect.arrayContaining([
        { path: '$.approved', code: 'type' }, { path: '$.count', code: 'range' }, { path: '$.reason', code: 'required' },
        { path: '$.extra', code: 'unknown' }, { path: '$.entries[0].choice', code: 'choice' },
      ]))
    expect(validateSchemaInput({ extra: 'text' }, { additionalProperties: { type: 'integer' } }))
      .toContainEqual({ path: '$.extra', code: 'type' })
    expect(validateSchemaInput([1], { items: false })).toContainEqual({ path: '$[0]', code: 'schema' })
    expect(validateSchemaInput({}, false)).toContainEqual({ path: '$', code: 'schema' })
  })

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
