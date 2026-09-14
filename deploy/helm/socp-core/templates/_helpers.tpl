{{- define "socp-core.commonLabels" -}}
app.kubernetes.io/part-of: socp-siem
app.kubernetes.io/managed-by: {{ .Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .Chart.Name .Chart.Version | quote }}
{{- end }}

{{- define "socp-core.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/part-of: socp-siem
{{- end }}

{{- define "socp-core.image" -}}
{{- $image := index .root.Values.images .workload.image -}}
{{- $repository := required (printf "images.%s.repository is required" .workload.image) $image.repository -}}
{{- $digest := required (printf "images.%s.digest is required" .workload.image) $image.digest -}}
{{- printf "%s@%s" $repository $digest -}}
{{- end }}

{{- define "socp-core.serviceAccountName" -}}
{{- required "serviceAccount.name is required" .Values.serviceAccount.name -}}
{{- end }}
