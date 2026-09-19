import ElMessageBox from 'element-plus/es/components/message-box/index.mjs'
import 'element-plus/es/components/message-box/style/css.mjs'
import { translate } from '../i18n'

export interface ConfirmDangerOptions {
  /** Dialog title; defaults to the localized `common.confirm` label. */
  title?: string
}

export interface PromptInputOptions extends ConfirmDangerOptions {
  /** Initial text shown in the input, replacing the native prompt default value. */
  defaultValue?: string
}

/**
 * Single entry point for destructive / irreversible confirmations and for the
 * short operator inputs that used native `window.confirm` / `window.prompt`,
 * which cannot be localized, themed, or styled and block the UI thread.
 * Resolves `true` only on an explicit confirmation; cancel, close, and Escape
 * all resolve `false` so callers keep the fail-closed
 * `if (!await confirmDanger(...)) return` shape.
 *
 * Button labels come from the workbench locale (not the Element Plus locale
 * pack) so they follow the same message keys as the rest of the UI.
 */
export function useConfirm() {
  async function confirmDanger(message: string, opts: ConfirmDangerOptions = {}): Promise<boolean> {
    try {
      await ElMessageBox.confirm(message, opts.title ?? translate('common.confirm'), {
        confirmButtonText: translate('common.confirm'),
        cancelButtonText: translate('common.cancel'),
        confirmButtonType: 'danger',
        type: 'warning',
        closeOnClickModal: false,
      })
      return true
    } catch {
      return false
    }
  }

  /**
   * Localized replacement for `window.prompt`, which cannot be themed, styled
   * or translated and blocks the UI thread. Resolves the entered text on
   * confirmation, and `null` on cancel, close or Escape so callers keep the
   * fail-closed `if (value === null) return` shape. An empty input is rejected
   * by the localized `common.inputRequired` hint and stays open for a second
   * attempt instead of resolving an empty string.
   */
  async function promptInput(message: string, opts: PromptInputOptions = {}): Promise<string | null> {
    try {
      const result = await ElMessageBox.prompt(message, opts.title ?? translate('common.confirm'), {
        confirmButtonText: translate('common.confirm'),
        cancelButtonText: translate('common.cancel'),
        inputValue: opts.defaultValue ?? '',
        closeOnClickModal: false,
        inputValidator: (value: string): boolean | string =>
          value && value.trim() ? true : translate('common.inputRequired'),
      })
      return result.value
    } catch {
      return null
    }
  }

  return { confirmDanger, promptInput }
}
