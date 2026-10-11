import type { LateEventPolicy, RuleCondition, RuleSpec } from '../api'

export type RuleEditorForm = {
  id: string; name: string; type: string; severity: string; message: string
  alertTitle: string; alertDescription: string; enabled: boolean; status: string
  window: string; keyField: string; groupBy: string; routingField: string
  lateAllowedLateness: string; lateHandling: 'DROP' | 'ACCEPT'; threshold: number | null
  valueField: string; warmup: number | null; baselineWindows: number | null; sigma: number | null
  minCount: number | null; mitre: string; version: string; owner: string
  contentPack: string; contentVersion: string; match: RuleCondition[]; matchAny: RuleCondition[][]
  whitelist: RuleCondition[]; steps: RuleCondition[][]
}

export const RULE_TYPES = ['pattern', 'threshold', 'correlation', 'correlation-set', 'baseline', 'rare']
export const ADVANCED_TYPES = ['correlation-set', 'baseline', 'rare']
export const STATEFUL_TYPES = ['threshold', 'correlation', 'correlation-set', 'baseline', 'rare']

export function emptyCondition(): RuleCondition { return { field: 'msg', op: 'contains', value: '' } }
export function clone<T>(value: T): T { return value == null ? value : JSON.parse(JSON.stringify(value)) as T }
function cloneConditionGroups(groups: RuleCondition[][] | undefined): RuleCondition[][] { return groups?.length ? clone(groups) : [] }

export function emptyRuleForm(): RuleEditorForm {
  return {
    id: '', name: '', type: 'pattern', severity: 'HIGH', message: '', alertTitle: '', alertDescription: '', enabled: false,
    status: 'DRAFT', window: '60s', keyField: 'src_ip', groupBy: 'src_ip', routingField: 'src_ip',
    lateAllowedLateness: '60s', lateHandling: 'DROP', threshold: 5, valueField: '', warmup: null,
    baselineWindows: null, sigma: null, minCount: null, mitre: '', version: '', owner: '', contentPack: '', contentVersion: '',
    match: [emptyCondition()], matchAny: [], whitelist: [], steps: [],
  }
}

export function textValue(value: unknown): string { return value == null ? '' : String(value) }
function numberValue(value: unknown): number | null { if (value == null || value === '') return null; const parsed = Number(value); return Number.isFinite(parsed) ? parsed : null }

export function formFromRule(rule: RuleSpec): RuleEditorForm {
  const policy = rule.lateEventPolicy && typeof rule.lateEventPolicy === 'object'
    ? rule.lateEventPolicy as LateEventPolicy
    : undefined
  const groupBy = textValue(rule.groupBy || rule.keyField || rule.routingField)
  return {
    id: textValue(rule.id), name: textValue(rule.name), type: textValue(rule.type || 'pattern'), severity: textValue(rule.severity || 'HIGH'),
    message: textValue(rule.message || rule.alert?.description), alertTitle: textValue(rule.alert?.title || rule.name),
    alertDescription: textValue(rule.alert?.description || rule.message), enabled: Boolean(rule.enabled),
    status: textValue(rule.status || (rule.enabled ? 'ACTIVE' : 'DRAFT')).toUpperCase(), window: textValue(rule.window),
    keyField: groupBy, groupBy, routingField: groupBy,
    lateAllowedLateness: textValue(policy?.allowedLateness || rule.window),
    lateHandling: String(policy?.handling || 'DROP').toUpperCase() === 'ACCEPT' ? 'ACCEPT' : 'DROP',
    threshold: numberValue(rule.threshold),
    valueField: textValue(rule.valueField), warmup: numberValue(rule.warmup), baselineWindows: numberValue(rule.baselineWindows),
    sigma: numberValue(rule.sigma), minCount: numberValue(rule.minCount), mitre: textValue(rule.mitre), version: textValue(rule.version),
    owner: textValue(rule.owner), contentPack: textValue(rule.contentPack), contentVersion: textValue(rule.contentVersion),
    match: clone(rule.match ?? []), matchAny: cloneConditionGroups(rule.matchAny), whitelist: clone(rule.whitelist ?? (rule.allowlist as RuleCondition[] | undefined) ?? []), steps: clone(rule.steps ?? []),
  }
}

export function normalizeRuleSpec(row: unknown): RuleSpec | null {
  if (!row || typeof row !== 'object') return null
  const candidate = row as Partial<RuleSpec>
  if (candidate.id == null || !String(candidate.id).trim() || candidate.name == null || !String(candidate.name).trim()) return null
  const status = textValue(candidate.status).toUpperCase()
  return {
    ...candidate, id: String(candidate.id), name: String(candidate.name), type: String(candidate.type ?? 'pattern'), severity: String(candidate.severity ?? 'HIGH'),
    status: status || (candidate.enabled ? 'ACTIVE' : 'DRAFT'), enabled: typeof candidate.enabled === 'boolean' ? candidate.enabled : status === 'ACTIVE',
  } as RuleSpec
}

/** Row-level problems per condition block, rendered inline by the builders. */
export type ConditionErrors = { match: Record<number, string>; matchAny: Record<number, Record<number, string>>; steps: Record<number, Record<number, string>>; whitelist: Record<number, string> }
export const emptyConditionErrors = (): ConditionErrors => ({ match: {}, matchAny: {}, steps: {}, whitelist: {} })
/** Blank, half-typed, or usable: a row with neither field nor value is blank. */
function conditionState(condition: RuleCondition): 'blank' | 'incomplete' | 'ok' {
  const field = textValue(condition.field).trim()
  const op = textValue(condition.op).trim()
  const value = textValue(condition.value).trim()
  if (!field && !value) return 'blank'
  if (!field || !op || !value) return 'incomplete'
  return 'ok'
}

function cleanConditions(conditions: RuleCondition[], errors: Record<number, string>, t: (key: string) => string): { rows: RuleCondition[]; blank: number; incomplete: number } {
  const rows: RuleCondition[] = []
  let blank = 0
  let incomplete = 0
  conditions.forEach((condition, index) => {
    const state = conditionState(condition)
    if (state === 'blank') { blank++; return }
    if (state === 'incomplete') { incomplete++; errors[index] = t('detect.conditionIncomplete'); rows.push(condition); return }
    // Literal values can contain meaningful spaces; nested metadata belongs to
    // the condition and must survive a visual edit or an advanced JSON edit.
    rows.push({ ...clone(condition), field: condition.field.trim(), op: condition.op.trim(), value: condition.value })
  })
  return { rows, blank, incomplete }
}

function cleanGroups(groups: RuleCondition[][], errors: Record<number, Record<number, string>>, t: (key: string) => string): { groups: RuleCondition[][]; blank: number; incomplete: number } {
  const kept: RuleCondition[][] = []
  let blank = 0
  let incomplete = 0
  groups.forEach((group, groupIndex) => {
    const rowErrors: Record<number, string> = {}
    const result = cleanConditions(group ?? [], rowErrors, t)
    blank += result.blank
    incomplete += result.incomplete
    if (result.incomplete) errors[groupIndex] = rowErrors
    // A group whose rows were all blank carries no meaning; keep the payload clean.
    if (result.rows.length) kept.push(result.rows)
  })
  return { groups: kept, blank, incomplete }
}

export type ConditionBlocks = { match: RuleCondition[]; matchAny: RuleCondition[][]; steps: RuleCondition[][]; whitelist: RuleCondition[] }

/**
 * Prunes blank rows and refuses to save half-typed conditions. Only the blocks
 * the editor actually renders for this rule type are enforced: a correlation
 * rule shows steps, never match/matchAny inputs.
 */
export function checkConditions(form: RuleEditorForm, t: (key: string) => string): { blocks: ConditionBlocks; blank: number; problem: string; errors: ConditionErrors } {
  const correlation = form.type === 'correlation'
  const errors = emptyConditionErrors()
  const incompleteBlocks: string[] = []
  const missingBlocks: string[] = []
  let blank = 0

  const flat = (key: 'match' | 'whitelist', label: string, rows: RuleCondition[], enforced: boolean): RuleCondition[] => {
    const found: Record<number, string> = {}
    const result = cleanConditions(rows, found, t)
    blank += result.blank
    if (result.incomplete && enforced) incompleteBlocks.push(label)
    errors[key] = enforced ? found : {}
    return result.rows
  }
  const nested = (key: 'matchAny' | 'steps', label: string, groups: RuleCondition[][], enforced: boolean, requireOne: boolean): RuleCondition[][] => {
    const found: Record<number, Record<number, string>> = {}
    const result = cleanGroups(groups, found, t)
    blank += result.blank
    if (result.incomplete && enforced) incompleteBlocks.push(label)
    if (requireOne && !result.groups.length) missingBlocks.push(label)
    errors[key] = enforced ? found : {}
    return result.groups
  }

  const blocks: ConditionBlocks = {
    match: flat('match', t('detect.allConditions'), form.match, !correlation),
    matchAny: nested('matchAny', t('detect.anyConditionGroup'), form.matchAny, !correlation, false),
    steps: nested('steps', t('detect.correlationSteps'), form.steps, correlation, correlation),
    whitelist: flat('whitelist', t('detect.editor.whitelist'), form.whitelist, true),
  }

  const messages: string[] = []
  if (incompleteBlocks.length) messages.push(`${t('detect.conditionIncomplete')} ${[...new Set(incompleteBlocks)].join('、')}`)
  // A correlation rule without a single step has nothing to correlate; the
  // section already explains that requirement next to the empty list.
  if (missingBlocks.length) messages.push(t('detect.addStepHint'))
  return { blocks, blank, problem: messages.join(' · '), errors }
}

function setOptional(target: Record<string, unknown>, key: string, value: unknown): void {
  if (value === null || value === undefined || (typeof value === 'string' && !value.trim())) delete target[key]
  else target[key] = value
}

export function buildRuleSpec(form: RuleEditorForm, sourceRule: RuleSpec | null, editingId: string | null, conditions: ConditionBlocks): Partial<RuleSpec> {
  const spec = (sourceRule ? clone(sourceRule) : {}) as Record<string, unknown>
  spec.name = form.name.trim(); spec.type = form.type; spec.severity = form.severity
  setOptional(spec, 'message', form.message); setOptional(spec, 'window', form.window); setOptional(spec, 'keyField', form.keyField)
  setOptional(spec, 'groupBy', form.groupBy)
  setOptional(spec, 'routingField', form.routingField); setOptional(spec, 'threshold', form.threshold); setOptional(spec, 'valueField', form.valueField)
  setOptional(spec, 'warmup', form.warmup); setOptional(spec, 'baselineWindows', form.baselineWindows); setOptional(spec, 'sigma', form.sigma)
  setOptional(spec, 'minCount', form.minCount); setOptional(spec, 'mitre', form.mitre); setOptional(spec, 'version', form.version)
  setOptional(spec, 'owner', form.owner); setOptional(spec, 'contentPack', form.contentPack); setOptional(spec, 'contentVersion', form.contentVersion)
  const rawPolicy = spec.lateEventPolicy
  const policy = rawPolicy && typeof rawPolicy === 'object' && !Array.isArray(rawPolicy)
    ? clone(rawPolicy) as Record<string, unknown>
    : {}
  if (STATEFUL_TYPES.includes(form.type) || Object.keys(policy).length) {
    setOptional(policy, 'allowedLateness', form.lateAllowedLateness)
    setOptional(policy, 'handling', form.lateHandling)
    if (Object.keys(policy).length) spec.lateEventPolicy = policy
    else delete spec.lateEventPolicy
  }
  const alert = (spec.alert && typeof spec.alert === 'object' ? clone(spec.alert) : {}) as Record<string, unknown>
  setOptional(alert, 'title', form.alertTitle); setOptional(alert, 'description', form.alertDescription)
  if (Object.keys(alert).length) spec.alert = alert; else delete spec.alert
  spec.match = conditions.match; spec.matchAny = conditions.matchAny; spec.steps = conditions.steps; spec.whitelist = conditions.whitelist
  delete spec.allowlist

  // Lifecycle transitions are separate operations. An edit cannot promote a
  // draft or disabled rule through the ordinary update endpoint.
  delete spec.status
  delete spec.revisionToken
  if (editingId) {
    spec.id = editingId
    spec.enabled = textValue(sourceRule?.status).toUpperCase() === 'ACTIVE' ? form.enabled : false
  } else {
    delete spec.id; spec.enabled = false
  }
  return spec as Partial<RuleSpec>
}
