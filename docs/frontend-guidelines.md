# Workbench frontend guidelines

This document owns the workbench internationalization and operator-form
conventions. Feature-specific behavior remains with the feature code and API
contracts.

## Internationalization

The workbench uses Vue I18n 11 in Composition API mode. The global instance is
in `frontend/apps/workbench/src/i18n/index.ts`; `locale-manager.ts` resolves
preferences and synchronizes Vue I18n, Element Plus, `<html lang>`, the
document title, and local storage.

Feature code uses semantic keys and locale-aware formatting:

```ts
const { t, d, n } = useI18n()
t('common.actions')
d(event.timestamp, 'dateTime')
n(total, 'integer')
```

Existing compatibility-facade callers remain supported. New code uses Vue
I18n's global Composition API directly.

### Locale resolution

Preferences resolve in this order:

1. authenticated profile locale;
2. compatibility profile-locale keys;
3. `socp-locale` in local storage;
4. browser preference;
5. `zh-CN`.

Only `zh-CN` and `en-US` are accepted. Once authenticated, the gateway is the
source of truth. Local auth can map usernames through `SOCP_AUTH_LOCALES`; OIDC
uses the provider locale claim. The gateway validates the value, stores it in
the signed session, forwards trusted `X-Socp-Locale`, and exposes it from
`/auth/session`. Requests send `Accept-Language` unless explicitly overridden.

Use stable keys such as `threat.importSuccess` and `common.delete`. Keep enum
and protocol values stable and translate them only at presentation. Do not
translate user-authored names or descriptions. Server errors expose a code and
parameters; the client resolves `errors.*`.

Chinese and English packs must have identical structure. Run:

```bash
python build/verify-frontend-i18n.py
cd frontend/apps/workbench && pnpm test && pnpm verify
```

The gate checks key parity, direct locale branching, known mojibake markers,
legacy keys, and literal key references. Component tests cover preference
fallback, interpolation, date/number formats, metadata, and switching.

## Form conventions

### Choose the container by editable field count

Ignore read-only rows when counting.

| Fields | Container | Width |
| --- | --- | --- |
| 1–2 | dialog | `440px` |
| 3–4 | dialog | `520px` |
| 5–8 | dialog | `640px` |
| 9–14 | drawer | `min(720px, 96vw)` |
| 15+, or a condition builder | inline workspace | content width |

Bulk paste/import uses `640px`; result tables use `720px` or wider. Fixed
desktop widths remain safe because the global dialog rule caps small screens.

### Put input labels above controls

Editable forms use `label-position="top"`, avoiding locale-dependent fixed
label columns. Read-only descriptions may keep side labels because operators
scan rather than fill them.

### Use the shared form structure

- `FormSection.vue` groups roughly eight or more fields into operator-named
  sections; do not section a small dialog.
- `FormGrid.vue` lays out one to four columns and collapses responsively.
- `FormField.vue` owns the label, required marker, help, unit, and field error.

Pair short fields; give textareas and long values a full row. Attach validation
to the offending field:

```vue
<FormField :label="t('…')" required :error="errors.name">
```

Use form-level `ActionFeedback` only for whole-operation failures such as a
rejected save or network error.

### Keep help text actionable

Help text should explain a downstream effect, unit/format, consequence, or
non-obvious constraint. Do not repeat the label as a placeholder. Describe a
validation pattern in words rather than showing its regular expression.

### Do not make operators edit raw JSON

Render objects as key/value rows and arrays as editable lists. A collapsed raw
JSON fallback is acceptable only for values the typed UI cannot represent; it
must not be the primary control.
