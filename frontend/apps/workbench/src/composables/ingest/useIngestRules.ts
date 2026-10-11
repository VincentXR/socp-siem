import { computed, ref, watch } from 'vue'
import { listParseRulesPage, deleteParseRule, type ParseRule } from '../../api'
import type { IngestWorkspace } from './useIngestWorkspace'

export function useIngestRules(workspace: IngestWorkspace) {
  const { loadList, loadStates, confirmWrite } = workspace
  const parseRulesLoading = computed(() => Boolean(loadStates.value.rules))
  const parseRules = ref<ParseRule[]>([])
  const rulePage = ref(1)
  const ruleSize = ref(20)
  const ruleTotal = ref(0)
  const ruleSearchDraft = ref('')
  const ruleQuery = ref('')
  const loadParseRules = () => loadList('rules',
    signal => listParseRulesPage(rulePage.value, ruleSize.value, ruleQuery.value, { signal }), value => {
      parseRules.value = value.items
      ruleTotal.value = value.total
      const lastPage = Math.max(1, Math.ceil(value.total / ruleSize.value))
      if (rulePage.value > lastPage) rulePage.value = lastPage
    })
  function applyRuleSearch() {
    ruleQuery.value = ruleSearchDraft.value.trim()
    if (rulePage.value === 1) void loadParseRules()
    else rulePage.value = 1
  }
  watch([rulePage, ruleSize], () => { void loadParseRules() })
  async function removeParseRule(id: string) {
    if (!await confirmWrite('ingest.deleteRuleConfirm')) return
    return workspace.run(async () => {
      await deleteParseRule(id)
      await loadParseRules()
    })
  }

  return { parseRules, parseRulesLoading, rulePage, ruleSize, ruleTotal, ruleSearchDraft, loadParseRules, applyRuleSearch, removeParseRule }
}
