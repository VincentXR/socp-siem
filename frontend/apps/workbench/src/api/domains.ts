import {
  assetStats, createAsset, deleteAsset, endpointStats, deleteEndpoint, listAssets, listEndpoints, updateAsset,
  caseStats, caseTimeline, exportCases, listCases, setCaseStatus,
  createIoc, deleteIoc, listIocs, tiMatch, tiStats,
  type Asset, type CaseInfo, type Endpoint, type Ioc, type TimelineEvent,
} from '../api'

/** Domain facades keep resource views independent from the legacy API barrel. */
export const assetApi = { list: listAssets, stats: assetStats, create: createAsset, update: updateAsset, remove: deleteAsset }
export const endpointApi = { list: listEndpoints, stats: endpointStats, remove: deleteEndpoint }
export const caseApi = { list: listCases, stats: caseStats, timeline: caseTimeline, updateStatus: setCaseStatus, export: exportCases }
export const threatIntelApi = { list: listIocs, stats: tiStats, match: tiMatch, create: createIoc, remove: deleteIoc }

export type { Asset, CaseInfo, Endpoint, Ioc, TimelineEvent }
