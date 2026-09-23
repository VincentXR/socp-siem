import {
  assetStats, relatedAssets, getEndpoint, endpointHistory, createAsset, deleteAsset, endpointStats, deleteEndpoint, getAsset, importAssets, listAssets, listEndpointEvents, listEndpoints, relatedEndpoints, updateAsset,
} from './assets'
import type { EndpointEvent } from './assets'
import { caseStats, caseTimeline, createCase, createCaseFromAlarm, exportCases, getCase, listCases, setCaseStatus } from './incidents'
import { createIoc, deleteIoc, importIocs, listIocs, setIocRevoked, tiMatch, tiStats, updateIoc } from './threat'
import type { Asset, CaseInfo, Endpoint, Ioc, TimelineEvent } from './models'

/** Domain facades keep resource views independent from the legacy API barrel. */
export const assetApi = { list: listAssets, related: relatedAssets, get: getAsset, stats: assetStats, create: createAsset, update: updateAsset, bulkImport: importAssets, remove: deleteAsset }
export const endpointApi = { list: listEndpoints, get: getEndpoint, history: endpointHistory, related: relatedEndpoints, events: listEndpointEvents, stats: endpointStats, remove: deleteEndpoint }
export const caseApi = { list: listCases, get: getCase, stats: caseStats, timeline: caseTimeline, create: createCase, createFromAlarm: createCaseFromAlarm, updateStatus: setCaseStatus, export: exportCases }
export const threatIntelApi = { list: listIocs, stats: tiStats, match: tiMatch, create: createIoc, update: updateIoc, setRevoked: setIocRevoked, bulkImport: importIocs, remove: deleteIoc }

export type { Asset, CaseInfo, Endpoint, Ioc, TimelineEvent }
export type { EndpointEvent }
