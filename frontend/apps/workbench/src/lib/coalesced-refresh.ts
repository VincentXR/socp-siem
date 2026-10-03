/** One in-flight refresh, one trailing refresh, bounded start frequency. */
export function coalescedRefresh(refresh: () => Promise<unknown>, minimumIntervalMs = 5_000) {
  let running = false
  let pending = false
  let disposed = false
  let lastStarted = -Infinity
  let timer: ReturnType<typeof setTimeout> | undefined
  function schedule(): void {
    if (disposed || running || timer !== undefined || !pending) return
    const delay = Math.max(0, minimumIntervalMs - (Date.now() - lastStarted))
    if (delay === 0) { void run(); return }
    timer = setTimeout(() => { timer = undefined; void run() }, delay)
  }
  async function run(): Promise<void> {
    if (disposed || running || !pending) return
    pending = false; running = true; lastStarted = Date.now()
    try { await refresh() } catch { /* Caller owns visible failure state. */ }
    finally { running = false; schedule() }
  }
  return {
    request() { if (!disposed) { pending = true; schedule() } },
    dispose() { disposed = true; pending = false; if (timer !== undefined) clearTimeout(timer) },
  }
}
