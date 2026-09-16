# Form conventions

Every create/edit surface in the workbench used to choose its own container,
width, and label direction. That produced eleven dialog widths between 420px
and 720px, five fixed label widths, and a majority of forms using side labels
while six used top labels. Nothing was wrong with any single choice; there was
simply no rule, so each new form copied whichever one was nearest.

This document is that rule. It is short on purpose.

## Pick the container by field count

Count the fields the operator actually fills in, ignoring read-only rows.

| Fields | Container | Width |
| --- | --- | --- |
| 1–2 | dialog | `440px` |
| 3–4 | dialog | `520px` |
| 5–8 | dialog | `640px` |
| 9–14 | drawer | `min(720px, 96vw)` |
| 15+, or contains a condition builder | inline workspace | fills the content area |

Two exceptions:

* **Bulk paste or import** uses `640px` regardless of field count. A dialog that
  holds a paste box is judged by how much text fits on one line, not by how many
  controls it has.
* **Preview or result tables** use `720px` or wider. A table squeezed into a
  form-width dialog is worse than a wide one.

Widths are fixed pixels rather than percentages because a dialog that grows with
the viewport puts a two-field form across a 4K monitor. The global
`.el-dialog` rule already caps width on small screens
(`max-width: calc(100vw - 32px)`), so a fixed value does not break mobile.

## Labels go on top for input, on the side for display

Input forms use `label-position="top"`.

Side labels need a fixed pixel label column, and there is no correct value for
it: the column has to fit the longest label in both locales, so it is either
wider than most labels need or it wraps. Top labels remove the column entirely
and let the control use the full cell width.

Read-only detail views (`el-descriptions`, definition lists rendered as `dt`/`dd`)
keep side labels. Those are scanned rather than filled in, and a wide label
column helps scanning.

## Structure the form with sections, grid with `FormGrid`

Three shared components carry the conventions:

* `FormSection.vue` — a number, a heading, and one sentence saying what the
  section is for. Use it when a form has more than about eight fields, or when
  the fields fall into groups an operator would name out loud ("identity" versus
  "ownership"). Do not add a section to a four-field dialog; the dialog title
  already names it.
* `FormGrid.vue` — takes `columns` 1–4 and drops to two then one column as the
  window narrows. Pair short fields two to a row; give textareas and long inputs
  their own row.
* `FormField.vue` — label, required marker, help text, unit, and **the error for
  that field**. Use it in place of a bare `el-form-item`.

## Report errors on the field, not above the form

A banner above a twenty-field form tells the operator that something is wrong
without telling them where. Bind the message to the offending field:

```vue
<FormField :label="t('…')" required :error="errors.name">
```

Keep the form-level `ActionFeedback` for failures that belong to the form as a
whole — a rejected save, a network error — not for validation.

## Write help text only where the label is not enough

A hint earns its place when it says something the label and placeholder cannot:

* what the value is used for downstream (`ipHint` — alerts are matched by it)
* a unit or accepted format the control does not imply
* a consequence (`secretRefHint` — never paste the secret itself)
* a constraint that is not obvious (`indexStrategyHint` — the cost of each choice)

Do not restate the label as the placeholder. If a field is genuinely
self-evident, it needs neither hint nor placeholder, and the form is quieter for
it.

## Never render raw JSON in an operator's form

`object` and `array` inputs used to render as a JSON textarea because the schema
said so. That is a developer's escape hatch, not an interface: it asks an
operator to know the serialization format, and it fails at submit rather than at
the field.

Render an `object` as a key/value row editor and an `array` as a list with an add
button. An advanced raw-JSON view is acceptable only as a collapsed fallback for
values the typed UI cannot express, and it must not be the primary control.

Show a constraint in words, not as the pattern that enforces it. `^[a-z0-9-]{1,63}$`
is a regular expression; "lowercase letters, digits and hyphens, 1 to 63
characters" is help text.
