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

test('role-prefixed viewer fails closed and approver keeps only approval surfaces', () => {
  const viewerKeys = getVisibleMenuGroups('ROLE_VIEWER').flatMap(group => group.items.map(item => item.key))
  const approverKeys = getVisibleMenuGroups('role_approver').flatMap(group => group.items.map(item => item.key))

  assert.ok(!viewerKeys.includes('detect'))
  assert.ok(!viewerKeys.includes('ingest'))
  assert.ok(approverKeys.includes('soar'))
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
  const keys = getVisibleMenuGroups('admin').flatMap(group => group.items.map(item => item.key))

  assert.ok(keys.includes('ingest'))
  assert.ok(keys.includes('detect'))
  assert.ok(keys.includes('ai'))
})

test('navigation removes groups that have no visible items', () => {
  const groups = getVisibleMenuGroups()
  assert.ok(groups.every(group => group.items.length > 0))
  assert.ok(!groups.some(group => group.items.some(item => item.key === 'health')))
})
