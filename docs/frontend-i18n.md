# Workbench internationalization

The workbench uses Vue I18n 11 in Composition API mode. The global instance lives in `src/i18n/index.ts`; `src/i18n/locale-manager.ts` owns locale preference resolution and synchronizes Vue I18n, Element Plus, `<html lang>`, the document title, and `localStorage`.

Feature code should use the compatibility facade:

```ts
const { t, d, n } = useI18n()
t('common.actions')
d(event.timestamp, 'dateTime')
n(total, 'integer')
```

The facade is intentionally temporary. New code may use Vue I18n's global Composition API directly, while existing `t`/locale callers can migrate without changing behavior.

## Locale resolution

Preferences are resolved in this order:

1. authenticated profile locale (when the session supplies one);
2. profile locale keys retained for compatibility;
3. `socp-locale` in local storage;
4. the browser's preferred language;
5. `zh-CN`.

Only `zh-CN` and `en-US` are accepted. A switch updates the Vue I18n locale, Element Plus locale, document metadata, and persisted preference. API requests send `Accept-Language` unless a caller explicitly provides it.

The gateway is the source of truth once a session is established. Local auth can
optionally configure `SOCP_AUTH_LOCALES` as a JSON username-to-locale map. The
gateway validates that locale, places it in the signed session claim, forwards
only the trusted `X-Socp-Locale` header, and includes `locale` in `/auth/session`.
OIDC sessions use the locale claim when the identity provider supplies one.

## Message rules

Use stable semantic keys such as `threat.importSuccess`, `ueba.scoreExplanation`, and `common.delete`. Keep fixed enum and protocol values as stable codes and translate them at the presentation boundary. User-authored rule names, case titles, and descriptions are never translated. Server errors should expose an error code and parameters; the client resolves `errors.*`.

Keep the Chinese and English message packs structurally identical. Run the gate from the repository root:

```bash
python build/verify-frontend-i18n.py
```

It checks key parity, legacy `inline.*` removal, direct locale branching, known mojibake markers, and literal translation key references. Component tests cover preference fallback, interpolation, fallback messages, date/number formats, document metadata, and locale switching.

```bash
cd frontend
pnpm --filter @socp/app-workbench test
pnpm build
```
