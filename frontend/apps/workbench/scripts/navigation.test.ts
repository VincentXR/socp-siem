import assert from 'node:assert/strict'
import test from 'node:test'
import { getVisibleMenuGroups } from '../src/app/navigation.ts'

test('viewer navigation keeps operational pages and hides configuration pages', () => {
  const keys = getVisibleMenuGroups('viewer').flatMap(group => group.items.map(item => item.key))

  assert.ok(keys.includes('overview'))
  assert.ok(keys.includes('alarms'))
  assert.ok(keys.includes('case'))
  assert.ok(keys.includes('threat-intel'))
  assert.ok(!keys.includes('ingest'))
  assert.ok(!keys.includes('detect'))
  assert.ok(!keys.includes('ai'))
  assert.ok(!keys.includes('soar'))
  assert.ok(!keys.includes('notify'))
})

test('analyst navigation exposes ingestion and detection management', () => {
  const keys = getVisibleMenuGroups('analyst').flatMap(group => group.items.map(item => item.key))

  assert.ok(keys.includes('ingest'))
  assert.ok(keys.includes('detect'))
  assert.ok(keys.includes('ai'))
  assert.ok(keys.includes('soar'))
  assert.ok(keys.includes('notify'))
})

test('role-prefixed viewer and unissuable approver roles fail closed', () => {
  const viewerKeys = getVisibleMenuGroups('ROLE_VIEWER').flatMap(group => group.items.map(item => item.key))
  const approverKeys = getVisibleMenuGroups('role_approver').flatMap(group => group.items.map(item => item.key))

  assert.ok(!viewerKeys.includes('detect'))
  assert.ok(!viewerKeys.includes('ingest'))
  assert.ok(!approverKeys.includes('soar'))
  assert.ok(!approverKeys.includes('detect'))
  assert.ok(!approverKeys.includes('notify'))
  assert.ok(!approverKeys.includes('ai'))
})

test('unknown roles fail closed like viewers', () => {
  const keys = getVisibleMenuGroups('external-unknown').flatMap(group => group.items.map(item => item.key))

  assert.ok(!keys.includes('detect'))
  assert.ok(!keys.includes('ai'))
  assert.ok(!keys.includes('soar'))
})

test('admin navigation exposes the same operator pages', () => {
  const groups = getVisibleMenuGroups('admin')
  const keys = groups.flatMap(group => group.items.map(item => item.key))

  assert.ok(keys.includes('ingest'))
  assert.ok(keys.includes('detect'))
  assert.ok(keys.includes('ai'))
  assert.deepEqual(groups[0]?.items.map(item => item.key), ['overview', 'alarms', 'case', 'search', 'situation'])
  assert.deepEqual(groups[1]?.items.map(item => item.key), ['detect', 'ueba', 'soar', 'ai'])
  assert.deepEqual(groups[2]?.items.map(item => item.key), ['endpoints', 'assets', 'threat-intel', 'attack'])
  assert.deepEqual(groups[3]?.items.map(item => item.key), ['refset', 'ingest', 'meta', 'notify'])
  assert.deepEqual(groups[4]?.items.map(item => item.key), ['compliance', 'report'])
  assert.equal(keys.filter(key => key === 'endpoints').length, 1)
  assert.equal(groups[4]?.defaultCollapsed, true)
  assert.equal(groups[3]?.defaultCollapsed, true)
  assert.equal(groups[3]?.secondary, true)
})

test('navigation removes groups that have no visible items', () => {
  const groups = getVisibleMenuGroups()
  assert.ok(groups.every(group => group.items.length > 0))
  assert.ok(!groups.some(group => group.items.some(item => item.key === 'health')))
})

test('delegated SOAR capabilities expose response navigation without unrelated domain administration', () => {
  const keys = getVisibleMenuGroups('viewer', undefined, ['soar:view', 'soar:approve']).flatMap(group => group.items.map(item => item.key))
  assert.ok(keys.includes('soar'))
  assert.ok(!keys.includes('detect'))
  assert.ok(!keys.includes('ingest'))
})

test('unknown SOAR permissions and unissuable roles do not open response navigation', () => {
  for (const [role, permissions] of [['viewer', ['soar:unknown']], ['approver', ['soar:approve']]] as const) {
    const keys = getVisibleMenuGroups(role, undefined, permissions).flatMap(group => group.items.map(item => item.key))
    assert.ok(!keys.includes('soar'))
  }
})
