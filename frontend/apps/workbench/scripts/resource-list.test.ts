import assert from 'node:assert/strict'
import test from 'node:test'
import { useResourceList } from '../src/composables/useResourceList.ts'
import { i18n } from '../src/i18n/index.ts'

interface Resource {
  id: string
  name: string
  status: string
}

const resources: Resource[] = [
  { id: '1', name: 'web-01', status: 'ONLINE' },
  { id: '2', name: 'db-01', status: 'OFFLINE' },
  { id: '3', name: 'web-02', status: 'ONLINE' },
]

test('resource list filters by keyword and domain-specific predicate', () => {
  const list = useResourceList<Resource>({
    searchFields: item => [item.id, item.name, item.status],
    filter: item => item.status === 'ONLINE',
  })
  list.setItems(resources)

  assert.deepEqual(list.filtered.value.map(item => item.id), ['1', '3'])
  list.keyword.value = 'web-02'
  assert.deepEqual(list.filtered.value.map(item => item.id), ['3'])
  list.keyword.value = 'db'
  assert.deepEqual(list.filtered.value, [])
})

test('resource list paginates and resets an empty page after replacement', () => {
  const list = useResourceList<Resource>({ searchFields: item => [item.name], pageSize: 2 })
  list.setItems(resources)
  list.page.value = 2
  assert.deepEqual(list.paged.value.map(item => item.id), ['3'])

  list.setItems([resources[0]])
  assert.equal(list.page.value, 1)
  assert.deepEqual(list.paged.value.map(item => item.id), ['1'])
})

test('resource list sorts the complete filtered set before pagination', () => {
  const list = useResourceList<Resource>({ searchFields: item => [item.name], pageSize: 2 })
  list.setItems(resources)
  list.onSortChange({ prop: 'name', order: 'descending' })

  assert.deepEqual(list.sorted.value.map(item => item.name), ['web-02', 'web-01', 'db-01'])
  assert.deepEqual(list.paged.value.map(item => item.name), ['web-02', 'web-01'])
  list.page.value = 2
  assert.deepEqual(list.paged.value.map(item => item.name), ['db-01'])
})

test('resource list sorting follows the active locale', () => {
  const list = useResourceList<Resource>({ searchFields: item => [item.name] })
  list.setItems([
    { id: 'a', name: '张', status: 'ONLINE' },
    { id: 'b', name: '李', status: 'ONLINE' },
    { id: 'c', name: '阿', status: 'ONLINE' },
  ])
  list.onSortChange({ prop: 'name', order: 'ascending' })

  const previousLocale = i18n.global.locale.value
  i18n.global.locale.value = 'zh-CN'
  assert.deepEqual(list.sorted.value.map(item => item.name), ['阿', '李', '张'])
  i18n.global.locale.value = 'en-US'
  assert.notDeepEqual(list.sorted.value.map(item => item.name), ['阿', '李', '张'])
  i18n.global.locale.value = previousLocale
})
