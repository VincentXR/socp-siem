import assert from 'node:assert/strict'
import test from 'node:test'
import { useResourceList } from '../src/composables/useResourceList.ts'

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
