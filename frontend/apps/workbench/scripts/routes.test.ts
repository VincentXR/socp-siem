import assert from 'node:assert/strict'
import test from 'node:test'
import { accessibleMenu, isMenuKey, menuForPath, pathForMenu } from '../src/app/routes.ts'

test('every known menu has a stable deep-link path', () => {
  assert.equal(pathForMenu('alarms'), '/alarms')
  assert.equal(menuForPath('/alarms'), 'alarms')
  assert.equal(menuForPath('/reference-sets/'), 'refset')
})

test('unknown paths and forbidden menus resolve safely', () => {
  assert.equal(menuForPath('/does-not-exist'), 'overview')
  assert.equal(accessibleMenu('detect', new Set(['overview', 'alarms'])), 'overview')
  assert.equal(accessibleMenu('alarms', new Set(['overview', 'alarms'])), 'alarms')
})

test('menu guard rejects arbitrary shell events', () => {
  assert.equal(isMenuKey('threat-intel'), true)
  assert.equal(isMenuKey('admin-secret'), false)
})
