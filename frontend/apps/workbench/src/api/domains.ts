import {
  assetStats, createAsset, deleteAsset, endpointStats, deleteEndpoint, importAssets, listAssets, listEndpoints, updateAsset,
} from './assets'
import { caseStats, caseTimeline, createCase, exportCases, listCases, setCaseStatus } from './incidents'
import { createIoc, deleteIoc, importIocs, listIocs, tiMatch, tiStats } from './threat'
import type { Asset, CaseInfo, Endpoint, Ioc, TimelineEvent } from './models'

/** Domain facades keep resource views independent from the legacy API barrel. */
export const assetApi = { list: listAssets, stats: assetStats, create: createAsset, update: updateAsset, bulkImport: importAssets, remove: deleteAsset }
export const endpointApi = { list: listEndpoints, stats: endpointStats, remove: deleteEndpoint }
export const caseApi = { list: listCases, stats: caseStats, timeline: caseTimeline, create: createCase, updateStatus: setCaseStatus, export: exportCases }
export const threatIntelApi = { list: listIocs, stats: tiStats, match: tiMatch, create: createIoc, bulkImport: importIocs, remove: deleteIoc }

export type { Asset, CaseInfo, Endpoint, Ioc, TimelineEvent }
