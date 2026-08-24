/**
 * Backwards-compatible API barrel.
 *
 * New code should import from the relevant domain module under `api/`.
 * Keeping this facade avoids a flag-day migration for existing views and plugins.
 */
export * from './api/core'
export * from './api/models'
export * from './api/auth'
export * from './api/alarms'
export * from './api/search'
export * from './api/detect'
export * from './api/soar'
export * from './api/reports'
export * from './api/assets'
export * from './api/soc'
export * from './api/threat'
export * from './api/attack'
export * from './api/notify'
export * from './api/incidents'
export * from './api/ingest'
export * from './api/health'
export * from './api/ai'
