import { onScopeDispose } from 'vue'

/** Atomic ownership for editor reads and the results of non-cancellable writes.
 * Aborting a read is an optimization; generation checks also reject transports
 * that resolve after abort and A→B→A identity reuse. */
export function useIdentitySession() {
  let generation = 0
  let controller = new AbortController()
  let disposed = false
  function begin(): number {
    controller.abort()
    controller = new AbortController()
    return ++generation
  }
  function isCurrent(owner: number): boolean { return !disposed && owner === generation }
  function dispose(): void { disposed = true; generation++; controller.abort() }
  onScopeDispose(dispose)
  return { begin, isCurrent, dispose, get generation() { return generation }, get signal() { return controller.signal } }
}
