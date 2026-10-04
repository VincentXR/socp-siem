import { afterEach, expect, it, vi } from 'vitest'
import { exportAlarms, listAlarmsPaged } from '../alarms'
import { downloadFile, get } from '../core'
import { alarmTransitionOptions } from '../../app/alarm-statuses'
vi.mock('../core', () => ({ get: vi.fn(), post: vi.fn(), put: vi.fn(), del: vi.fn(), requestJson: vi.fn(), downloadFile: vi.fn() }))
afterEach(() => vi.resetAllMocks())

it('normalizes ALL identically at the list and export HTTP boundaries', async () => {
  await listAlarmsPaged(1, 20, 'test', 'HIGH', 'ALL')
  await exportAlarms('csv', { q: 'test', severity: 'HIGH', status: 'ALL' })
  const list = new URL(String(vi.mocked(get).mock.calls[0][0]), 'http://localhost')
  const exported = new URL(String(vi.mocked(downloadFile).mock.calls[0][0]), 'http://localhost')
  expect(list.searchParams.has('status')).toBe(false)
  expect(exported.searchParams.has('status')).toBe(false)
  expect(list.searchParams.get('q')).toBe(exported.searchParams.get('q'))
  await exportAlarms('json', { status: 'SUPPRESSED' })
  expect(downloadFile).toHaveBeenLastCalledWith('/alert-web/api/alarms/export?format=json&status=SUPPRESSED', 'alarms.json')
})

it('only offers reachable transitions while allowing an idempotent current-state submission', () => {
  expect(alarmTransitionOptions('CLOSED')).toEqual(['CLOSED', 'INVESTIGATING'])
  expect(alarmTransitionOptions('SUPPRESSED')).toEqual(['SUPPRESSED', 'INVESTIGATING', 'CLOSED'])
  expect(alarmTransitionOptions('unknown')).toEqual([])
})
