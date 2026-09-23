import { describe, expect, it } from 'vitest'
import { appendSearchFilter } from '../search-query'

describe('investigation field pivots', () => {
  it('constrains every OR branch without moving a pipeline command into the filter', () => {
    expect(appendSearchFilter('source=auth OR source=web | count by host', 'host', 'edge'))
      .toBe('(source=auth OR source=web) AND host="edge" | count by host')
  })

  it('preserves literal backslashes, quotes and pipes in event values', () => {
    expect(appendSearchFilter('msg="a|b" | head 10', 'path', String.raw`C:\logs\"auth"`))
      .toBe(String.raw`(msg="a|b") AND path="C:\\logs\\\"auth\"" | head 10`)
  })

  it('inserts an editable field before aggregation and replaces a match-all filter', () => {
    expect(appendSearchFilter('* | top host 5', 'src_ip')).toBe('src_ip= | top host 5')
  })
})
