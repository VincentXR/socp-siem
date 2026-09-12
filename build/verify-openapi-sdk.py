#!/usr/bin/env python3
"""Generate and exercise a typed SOAR TypeScript client.

The checked-in OpenAPI document is a reviewed offline snapshot, while a
running Spring application is the deployment contract.  This gate therefore
does three things:

* validates the snapshot's references, operations, response envelopes and
  authentication/concurrency headers;
* generates a small dependency-free TypeScript client from that document and
  compiles it with the repository's TypeScript compiler; and
* when a runtime URL is supplied, fetches ``/v3/api-docs`` and runs the
  generated client through a real session-cookie flow, including a successful
  If-Match update, a stale If-Match 412, an ETag response, and a 404 error
  envelope.

The generated source is written under ``.cache`` by default and is evidence,
not product source.  It deliberately has no third-party runtime dependency so
the check is reproducible in the existing frontend toolchain.

Environment:
  SOAR_OPENAPI_SPEC             snapshot path (default docs/soar-openapi.yaml)
  SOAR_OPENAPI_BASE_URL         SOAR service/gateway base; enables runtime smoke
  SOAR_OPENAPI_RUNTIME_URL      alias for the base URL
  SOCP_GATEWAY_URL              login endpoint when the base is a routed service
  SOAR_OPENAPI_REQUIRE_RUNTIME  fail when no runtime URL is provided
  SOAR_OPENAPI_OUTPUT           generated source/evidence directory
  SOAR_VERIFY_USERNAME/PASSWORD login credentials (admin/admin123 by default)
  SOAR_VERIFY_TENANT            tenant header (default default)
"""

from __future__ import annotations

import argparse
import datetime
from dataclasses import dataclass
import hashlib
import http.cookies
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import urllib.error
import urllib.request
from typing import Any, Iterable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SPEC = ROOT / "docs" / "soar-openapi.yaml"
METHODS = {"get", "post", "put", "patch", "delete", "head", "options", "trace"}
HTTP_STATUS_RE = re.compile(r"^[1-5][0-9]{2}$|^(default)$")
PATH_PARAMETER_RE = re.compile(r"\{([^}]+)\}")


@dataclass(frozen=True)
class Parameter:
    name: str
    location: str
    required: bool
    schema: dict[str, Any]

    @property
    def ts_name(self) -> str:
        return ts_identifier(self.name, lower_camel=True)


@dataclass(frozen=True)
class Operation:
    method: str
    path: str
    name: str
    parameters: tuple[Parameter, ...]
    body_schema: dict[str, Any] | None
    body_required: bool
    response_schema: dict[str, Any] | None


def load_yaml(path: Path) -> dict[str, Any]:
    try:
        import yaml  # type: ignore
    except ImportError as error:  # pragma: no cover - environment dependent
        raise RuntimeError("PyYAML is required: python -m pip install pyyaml") from error
    try:
        with path.open("r", encoding="utf-8") as source:
            document = yaml.safe_load(source)
    except OSError as error:
        raise RuntimeError(f"cannot read OpenAPI document {path}: {error}") from error
    if not isinstance(document, dict):
        raise RuntimeError(f"OpenAPI document {path} is not an object")
    return document


def canonical_json(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def document_hash(document: dict[str, Any]) -> str:
    return hashlib.sha256(canonical_json(document).encode("utf-8")).hexdigest()


def json_pointer(document: dict[str, Any], reference: str) -> Any:
    if not reference.startswith("#/"):
        raise ValueError(f"external OpenAPI reference is not supported: {reference}")
    current: Any = document
    for part in reference[2:].split("/"):
        part = part.replace("~1", "/").replace("~0", "~")
        if not isinstance(current, dict) or part not in current:
            raise ValueError(f"unresolved OpenAPI reference: {reference}")
        current = current[part]
    return current


def resolve(document: dict[str, Any], value: Any) -> Any:
    if isinstance(value, dict) and "$ref" in value:
        target = resolve(document, json_pointer(document, str(value["$ref"])))
        siblings = {key: item for key, item in value.items() if key != "$ref"}
        if siblings and isinstance(target, dict):
            merged = dict(target)
            merged.update(siblings)
            return merged
        return target
    return value


def ref_name(reference: str) -> str:
    return reference.rsplit("/", 1)[-1]


def ts_identifier(value: str, lower_camel: bool = False) -> str:
    words = [word for word in re.split(r"[^A-Za-z0-9]+", value) if word]
    if not words:
        return "value"
    result = "".join(word[:1].upper() + word[1:] for word in words)
    if result[:1].isdigit():
        result = "Value" + result
    if lower_camel:
        result = result[:1].lower() + result[1:]
    return result


def canonical_path(path: str) -> str:
    return PATH_PARAMETER_RE.sub("{}", path)


def operation_name(method: str, path: str, operation: dict[str, Any]) -> str:
    supplied = operation.get("operationId")
    if isinstance(supplied, str) and supplied.strip():
        return ts_identifier(supplied.strip(), lower_camel=True)
    words: list[str] = [method.lower()]
    for segment in path.strip("/").split("/"):
        if not segment or segment.startswith("{"):
            if segment.startswith("{"):
                words.append(ts_identifier(segment[1:-1]))
            continue
        words.append(ts_identifier(segment))
    return "".join(word if index == 0 else word[:1].upper() + word[1:]
                   for index, word in enumerate(words))


def iter_operations(document: dict[str, Any]) -> Iterable[tuple[str, str, dict[str, Any]]]:
    paths = document.get("paths")
    if not isinstance(paths, dict):
        return
    for path in sorted(paths):
        item = paths[path]
        if not isinstance(item, dict):
            continue
        for method in sorted(METHODS & set(item), key=lambda value: (value, path)):
            operation = item[method]
            if isinstance(operation, dict):
                yield path, method, operation


def operation_parameters(document: dict[str, Any], path: str,
                         operation: dict[str, Any]) -> tuple[Parameter, ...]:
    item = document.get("paths", {}).get(path, {})
    raw_parameters: list[Any] = []
    if isinstance(item, dict):
        raw_parameters.extend(item.get("parameters", []) or [])
    raw_parameters.extend(operation.get("parameters", []) or [])
    parameters: list[Parameter] = []
    seen: set[tuple[str, str]] = set()
    for raw in raw_parameters:
        parameter = resolve(document, raw)
        if not isinstance(parameter, dict):
            continue
        name = str(parameter.get("name", ""))
        location = str(parameter.get("in", ""))
        if not name or location not in {"path", "query", "header", "cookie"}:
            continue
        key = (name.lower(), location)
        if key in seen:
            continue
        seen.add(key)
        schema = resolve(document, parameter.get("schema", {}))
        parameters.append(Parameter(name, location, bool(parameter.get("required", False)),
                                    schema if isinstance(schema, dict) else {}))
    return tuple(parameters)


def media_schema(document: dict[str, Any], content: Any) -> dict[str, Any] | None:
    if not isinstance(content, dict):
        return None
    for media_type in ("application/json", "application/*+json", "text/event-stream"):
        entry = content.get(media_type)
        if isinstance(entry, dict) and "schema" in entry:
            schema = resolve(document, entry["schema"])
            return schema if isinstance(schema, dict) else None
    for entry in content.values():
        if isinstance(entry, dict) and "schema" in entry:
            schema = resolve(document, entry["schema"])
            return schema if isinstance(schema, dict) else None
    return None


def response_schema(document: dict[str, Any], operation: dict[str, Any]) -> dict[str, Any] | None:
    responses = operation.get("responses")
    if not isinstance(responses, dict):
        return None
    for status in sorted(responses, key=lambda value: str(value)):
        if not str(status).startswith("2"):
            continue
        response = resolve(document, responses[status])
        if isinstance(response, dict):
            schema = media_schema(document, response.get("content"))
            if schema is not None:
                return schema
    return None


def body_schema(document: dict[str, Any], operation: dict[str, Any]) -> tuple[dict[str, Any] | None, bool]:
    request_body = operation.get("requestBody")
    if request_body is None:
        return None, False
    request_body = resolve(document, request_body)
    if not isinstance(request_body, dict):
        return None, bool(operation.get("requestBody", {}).get("required", False))
    content = request_body.get("content")
    schema = media_schema(document, content)
    # Raw JsonNode payloads are intentional at a few extension boundaries
    # (for example artifact content).  Keep a typed ``unknown`` body in the
    # generated client instead of dropping the request body entirely.
    if schema is None and isinstance(content, dict) and content:
        schema = {}
    return schema, bool(request_body.get("required", False))


def collect_refs(value: Any) -> Iterable[str]:
    if isinstance(value, dict):
        reference = value.get("$ref")
        if isinstance(reference, str):
            yield reference
        for child in value.values():
            yield from collect_refs(child)
    elif isinstance(value, list):
        for child in value:
            yield from collect_refs(child)


def envelope_schema(document: dict[str, Any]) -> dict[str, Any] | None:
    schemas = document.get("components", {}).get("schemas", {})
    if isinstance(schemas, dict):
        candidate = schemas.get("ApiResult")
        if isinstance(candidate, dict) and _is_api_result_schema(candidate):
            return candidate
        for candidate in schemas.values():
            if isinstance(candidate, dict) and _is_api_result_schema(candidate):
                return candidate
    return None


def _is_api_result_schema(candidate: dict[str, Any]) -> bool:
    """Recognise both reviewed schemas and Springdoc's bean-derived schemas.

    The checked-in contract marks envelope fields as required.  Springdoc
    derives the same Java bean without emitting ``required`` unless a
    ``@NotNull`` annotation is present, so a runtime document legitimately
    exposes the fields under ``properties`` only.  The verifier still checks
    that all three fields are described rather than accepting a name-only
    schema.
    """
    properties = candidate.get("properties")
    property_names = set(properties) if isinstance(properties, dict) else set()
    required_names = set(candidate.get("required", []))
    return {"code", "message", "timestamp"}.issubset(property_names | required_names)


def validate_document(document: dict[str, Any], label: str = "OpenAPI") -> list[str]:
    errors: list[str] = []
    if not str(document.get("openapi", "")).startswith("3."):
        errors.append(f"{label}: openapi version must be 3.x")
    if not isinstance(document.get("info"), dict):
        errors.append(f"{label}: info is missing")
    paths = document.get("paths")
    if not isinstance(paths, dict) or not paths:
        errors.append(f"{label}: paths is empty")
    components = document.get("components")
    if not isinstance(components, dict):
        errors.append(f"{label}: components is missing")
    schemes = components.get("securitySchemes", {}) if isinstance(components, dict) else {}
    cookie_auth = schemes.get("cookieAuth") if isinstance(schemes, dict) else None
    if not isinstance(cookie_auth, dict) or cookie_auth.get("type") != "apiKey" \
            or cookie_auth.get("in") != "cookie" or cookie_auth.get("name") != "SOCP_SESSION":
        errors.append(f"{label}: cookieAuth must be apiKey cookie SOCP_SESSION")
    tenant_auth = schemes.get("tenantHeader") if isinstance(schemes, dict) else None
    if not isinstance(tenant_auth, dict) or tenant_auth.get("type") != "apiKey" \
            or tenant_auth.get("in") != "header" or tenant_auth.get("name") != "X-Tenant-Id":
        errors.append(f"{label}: tenantHeader must be apiKey header X-Tenant-Id")
    if envelope_schema(document) is None:
        errors.append(f"{label}: no ApiResult-like schema with code/message/timestamp")

    for reference in sorted(set(collect_refs(document))):
        try:
            json_pointer(document, reference)
        except ValueError as error:
            errors.append(f"{label}: {error}")

    names: set[str] = set()
    for path, method, operation in iter_operations(document):
        name = operation_name(method, path, operation)
        if name in names:
            errors.append(f"{label}: duplicate generated operation name {name}")
        names.add(name)
        placeholders = set(PATH_PARAMETER_RE.findall(path))
        parameters = operation_parameters(document, path, operation)
        path_params = {parameter.name for parameter in parameters if parameter.location == "path"}
        missing = placeholders - path_params
        if missing:
            errors.append(f"{label}: {method.upper()} {path} misses path parameters {sorted(missing)}")
        for parameter in parameters:
            if parameter.location == "path" and not parameter.required:
                errors.append(f"{label}: path parameter {parameter.name} on {method.upper()} {path} is optional")
        responses = operation.get("responses")
        if not isinstance(responses, dict) or not responses:
            errors.append(f"{label}: {method.upper()} {path} has no responses")
            continue
        for status, raw_response in responses.items():
            if not HTTP_STATUS_RE.match(str(status)):
                errors.append(f"{label}: invalid response status {status} on {method.upper()} {path}")
                continue
            response = resolve(document, raw_response)
            if not isinstance(response, dict) or not response.get("description"):
                errors.append(f"{label}: response {status} on {method.upper()} {path} lacks description")
        request_body = operation.get("requestBody")
        if request_body is not None:
            resolved_body = resolve(document, request_body)
            if not isinstance(resolved_body, dict) or not isinstance(resolved_body.get("content"), dict) \
                    or not resolved_body.get("content"):
                errors.append(f"{label}: request body on {method.upper()} {path} has no schema")

    version_methods = [
        (path, method, operation)
        for path, method, operation in iter_operations(document)
        if "/versions/{" in path and method in {"get", "put"}
    ]
    if not any(any(parameter.name.lower() == "if-match" and parameter.location == "header"
                   for parameter in operation_parameters(document, path, operation))
               for path, method, operation in version_methods if method == "put"):
        errors.append(f"{label}: version PUT is missing If-Match")
    if not any("etag" in {str(header).lower() for header in (
            resolve(document, operation.get("responses", {}).get("200", {})) or {}).get("headers", {})}
               for path, method, operation in version_methods if method in {"get", "put"}):
        errors.append(f"{label}: version response is missing ETag")
    return errors


def compare_runtime(snapshot: dict[str, Any], runtime: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    snapshot_ops = {(canonical_path(path), method)
                    for path, method, _ in iter_operations(snapshot)}
    runtime_ops = {(canonical_path(path), method)
                   for path, method, _ in iter_operations(runtime)}
    missing = sorted(snapshot_ops - runtime_ops)
    if missing:
        errors.append(f"runtime OpenAPI misses snapshot operations: {missing[:12]}")
    runtime_schemes = runtime.get("components", {}).get("securitySchemes", {})
    cookie = runtime_schemes.get("cookieAuth") if isinstance(runtime_schemes, dict) else None
    if not isinstance(cookie, dict) or cookie.get("name") != "SOCP_SESSION":
        errors.append("runtime OpenAPI does not describe the SOCP_SESSION cookie")
    tenant = runtime_schemes.get("tenantHeader") if isinstance(runtime_schemes, dict) else None
    if not isinstance(tenant, dict) or tenant.get("name") != "X-Tenant-Id":
        errors.append("runtime OpenAPI does not describe X-Tenant-Id")
    runtime_version = [
        (path, method, operation)
        for path, method, operation in iter_operations(runtime)
        if "/versions/" in path and method in {"get", "put"}
    ]
    def resolved_parameters(operation: dict[str, Any]) -> list[dict[str, Any]]:
        result: list[dict[str, Any]] = []
        for raw in operation.get("parameters", []) or []:
            value = resolve(runtime, raw)
            if isinstance(value, dict):
                result.append(value)
        return result

    def response_headers(operation: dict[str, Any]) -> set[str]:
        headers: set[str] = set()
        responses = operation.get("responses", {})
        if not isinstance(responses, dict):
            return headers
        for raw in responses.values():
            value = resolve(runtime, raw)
            if isinstance(value, dict) and isinstance(value.get("headers"), dict):
                headers.update(str(header).lower() for header in value["headers"])
        return headers

    if not any(any(str(parameter.get("name", "")).lower() == "if-match"
                   and parameter.get("in") == "header"
                   for parameter in resolved_parameters(operation))
               for _, method, operation in runtime_version if method == "put"):
        errors.append("runtime OpenAPI version PUT does not describe If-Match")
    if not any("etag" in response_headers(operation)
               for _, _, operation in runtime_version):
        errors.append("runtime OpenAPI version response does not describe ETag")
    return errors


def ts_type(document: dict[str, Any], schema: Any, generic_api_result: bool = True) -> str:
    schema = resolve(document, schema)
    if not isinstance(schema, dict):
        return "unknown"
    reference = schema.get("$ref")
    if isinstance(reference, str):
        name = ref_name(reference)
        if generic_api_result and name == "ApiResult":
            return "SoarApiResult<unknown>"
        return "Soar" + ts_identifier(name)
    if "const" in schema:
        return json.dumps(schema["const"])
    if "oneOf" in schema or "anyOf" in schema:
        values = schema.get("oneOf", schema.get("anyOf", []))
        return " | ".join(ts_type(document, item) for item in values) or "unknown"
    if "allOf" in schema:
        return " & ".join(ts_type(document, item) for item in schema["allOf"]) or "unknown"
    schema_type = schema.get("type")
    if isinstance(schema_type, list):
        types = [item for item in schema_type if item != "null"]
        result = ts_type(document, {**schema, "type": types[0]} if types else {})
        if "null" in schema_type:
            result += " | null"
        return result
    if "enum" in schema:
        values = [json.dumps(item) for item in schema.get("enum", [])]
        result = " | ".join(values) or "string"
        return result + (" | null" if schema.get("nullable") else "")
    if schema_type == "array":
        result = f"Array<{ts_type(document, schema.get('items', {}))}>"
    elif schema_type == "object" or "properties" in schema:
        properties = schema.get("properties")
        if isinstance(properties, dict) and properties:
            required = set(schema.get("required", []))
            fields = []
            for name in sorted(properties):
                key = json.dumps(name) if not re.match(r"^[A-Za-z_$][A-Za-z0-9_$]*$", name) else name
                optional = "" if name in required else "?"
                fields.append(f"{key}{optional}: {ts_type(document, properties[name])};")
            result = "{ " + " ".join(fields) + " }"
        else:
            additional = schema.get("additionalProperties")
            result = f"Record<string, {ts_type(document, additional)}>" if isinstance(additional, dict) else "Record<string, unknown>"
    elif schema_type in {"integer", "number"}:
        result = "number"
    elif schema_type == "boolean":
        result = "boolean"
    elif schema_type == "string":
        result = "string"
    else:
        result = "unknown"
    return result + (" | null" if schema.get("nullable") and "null" not in result else "")


def generate_models(document: dict[str, Any]) -> list[str]:
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict):
        return []
    lines: list[str] = []
    for name in sorted(schemas):
        schema = resolve(document, schemas[name])
        type_name = "Soar" + ts_identifier(name)
        if name == "ApiResult":
            lines.extend([
                "export interface SoarApiResult<T = unknown> {",
                "  code: number;",
                "  message: string;",
                "  data?: T | null;",
                "  traceId?: string | null;",
                "  timestamp: string;",
                "}",
                "",
            ])
            continue
        if isinstance(schema, dict) and (schema.get("type") == "object" or "properties" in schema) \
                and not schema.get("allOf"):
            lines.append(f"export interface {type_name} {{")
            properties = schema.get("properties", {})
            required = set(schema.get("required", []))
            if isinstance(properties, dict):
                for property_name in sorted(properties):
                    key = json.dumps(property_name) if not re.match(r"^[A-Za-z_$][A-Za-z0-9_$]*$", property_name) else property_name
                    optional = "" if property_name in required else "?"
                    lines.append(f"  {key}{optional}: {ts_type(document, properties[property_name])};")
            if not properties:
                lines.append("  [key: string]: unknown;")
            lines.extend(["}", ""])
        else:
            lines.extend([f"export type {type_name} = {ts_type(document, schema)};", ""])
    return lines


def extract_operations(document: dict[str, Any]) -> list[Operation]:
    operations: list[Operation] = []
    used: set[str] = set()
    for path, method, raw in iter_operations(document):
        name = operation_name(method, path, raw)
        if name in used:
            suffix = 2
            while f"{name}{suffix}" in used:
                suffix += 1
            name = f"{name}{suffix}"
        used.add(name)
        schema, required = body_schema(document, raw)
        operations.append(Operation(method.upper(), path, name,
                                    operation_parameters(document, path, raw), schema,
                                    required, response_schema(document, raw)))
    return operations


def generate_client(document: dict[str, Any]) -> tuple[str, list[Operation]]:
    operations = extract_operations(document)
    lines = [
        "/* eslint-disable */",
        "/** Generated by build/verify-openapi-sdk.py; do not edit by hand. */",
        f"export const OPENAPI_SPEC_SHA256 = {json.dumps(document_hash(document))};",
        "",
        "export interface SoarSdkResponse<T> {",
        "  status: number;",
        "  headers: Headers;",
        "  body: T;",
        "}",
        "",
        "export interface SoarClientOptions {",
        "  baseUrl: string;",
        "  fetch?: typeof fetch;",
        "  // Browser callers rely on the HttpOnly SOCP_SESSION cookie via credentials.",
        "  // Node/CI callers may provide a Cookie header through headers for smoke tests.",
        "  headers?: Record<string, string>;",
        "}",
        "",
        "type RequestPlan = {",
        "  path?: Record<string, string | number>;",
        "  query?: Record<string, unknown>;",
        "  headers?: Record<string, unknown>;",
        "  body?: unknown;",
        "};",
        "",
        "export class SoarClient {",
        "  private readonly baseUrl: string;",
        "  private readonly fetchImpl: typeof fetch;",
        "  private readonly defaultHeaders: Record<string, string>;",
        "",
        "  constructor(options: SoarClientOptions) {",
        "    this.baseUrl = options.baseUrl.replace(/\\/$/, '');",
        "    this.fetchImpl = options.fetch ?? fetch;",
        "    this.defaultHeaders = { Accept: 'application/json', ...(options.headers ?? {}) };",
        "  }",
        "",
        "  protected async request<T>(method: string, path: string, plan: RequestPlan): Promise<SoarSdkResponse<T>> {",
        "    const rendered = path.replace(/\\{([^}]+)\\}/g, (_match, key: string) =>",
        "      encodeURIComponent(String(plan.path?.[key])));",
        "    const url = new URL(this.baseUrl + rendered);",
        "    for (const [key, value] of Object.entries(plan.query ?? {})) {",
        "      if (value === undefined || value === null) continue;",
        "      if (Array.isArray(value)) value.forEach(item => url.searchParams.append(key, String(item)));",
        "      else url.searchParams.set(key, String(value));",
        "    }",
        "    const headers: Record<string, string> = { ...this.defaultHeaders };",
        "    for (const [key, value] of Object.entries(plan.headers ?? {})) {",
        "      if (value !== undefined && value !== null) headers[key] = String(value);",
        "    }",
        "    const init: RequestInit = { method, headers, credentials: 'include' };",
        "    if (plan.body !== undefined) {",
        "      headers['Content-Type'] = 'application/json';",
        "      init.body = JSON.stringify(plan.body);",
        "    }",
        "    const response = await this.fetchImpl(url.toString(), init);",
        "    const text = await response.text();",
        "    let body: unknown = {};",
        "    if (text) { try { body = JSON.parse(text); } catch { body = text; } }",
        "    return { status: response.status, headers: response.headers, body: body as T };",
        "  }",
        "}",
        "",
    ]
    lines.extend(generate_models(document))
    for operation in operations:
        params_name = ts_identifier(operation.name) + "Params"
        lines.append(f"export interface {params_name} {{")
        for parameter in operation.parameters:
            optional = "" if parameter.required else "?"
            lines.append(f"  {parameter.ts_name}{optional}: {ts_type(document, parameter.schema)};")
        if operation.body_schema is not None:
            optional = "" if operation.body_required else "?"
            lines.append(f"  body{optional}: {ts_type(document, operation.body_schema)};")
        lines.extend(["}", ""])
    lines.append("export class GeneratedSoarClient extends SoarClient {")
    for operation in operations:
        params_name = ts_identifier(operation.name) + "Params"
        result_type = ts_type(document, operation.response_schema or {})
        required = any(parameter.required for parameter in operation.parameters) or operation.body_required
        signature = f"params: {params_name}" if required else f"params: {params_name} = {{}}"
        lines.extend([
            f"  async {operation.name}({signature}): Promise<SoarSdkResponse<{result_type}>> {{",
            "    return this.request<" + result_type + ">(\"" + operation.method + "\", " + json.dumps(operation.path) + ", {",
        ])
        path_params = [parameter for parameter in operation.parameters if parameter.location == "path"]
        query_params = [parameter for parameter in operation.parameters if parameter.location == "query"]
        header_params = [parameter for parameter in operation.parameters if parameter.location in {"header", "cookie"}]
        if path_params:
            lines.append("      path: {" + ", ".join(f"{json.dumps(p.name)}: params.{p.ts_name}" for p in path_params) + "},")
        if query_params:
            lines.append("      query: {" + ", ".join(f"{json.dumps(p.name)}: params.{p.ts_name}" for p in query_params) + "},")
        if header_params:
            lines.append("      headers: {" + ", ".join(f"{json.dumps(p.name)}: params.{p.ts_name}" for p in header_params) + "},")
        if operation.body_schema is not None:
            lines.append("      body: params.body,")
        lines.extend(["    });", "  }", ""])
    lines.append("}")
    return "\n".join(lines) + "\n", operations


JAVA_RESERVED = {
    "abstract", "assert", "boolean", "break", "byte", "case", "catch",
    "char", "class", "const", "continue", "default", "do", "double",
    "else", "enum", "extends", "final", "finally", "float", "for", "goto",
    "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "package", "private", "protected", "public", "return",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this",
    "throw", "throws", "transient", "try", "void", "volatile", "while",
    "true", "false", "null", "record", "sealed", "permits", "non-sealed",
}


def java_field_name(value: str) -> str:
    name = ts_identifier(value, lower_camel=True)
    return name + "_" if name in JAVA_RESERVED else name


def java_type(document: dict[str, Any], schema: Any) -> str:
    schema = resolve(document, schema)
    if not isinstance(schema, dict):
        return "Object"
    reference = schema.get("$ref")
    if isinstance(reference, str):
        name = ref_name(reference)
        return "SoarApiResult<Object>" if name == "ApiResult" else "Soar" + ts_identifier(name)
    if "oneOf" in schema or "anyOf" in schema or "allOf" in schema:
        return "Object"
    schema_type = schema.get("type")
    if isinstance(schema_type, list):
        schema_type = next((item for item in schema_type if item != "null"), None)
    if schema_type == "array":
        return f"java.util.List<{java_type(document, schema.get('items', {}))}>"
    if schema_type in {"object", None}:
        return "java.util.Map<String, Object>"
    if schema_type == "integer":
        return "Long" if schema.get("format") == "int64" else "Integer"
    if schema_type == "number":
        return "Double"
    if schema_type == "boolean":
        return "Boolean"
    if schema_type == "string":
        return "String"
    return "Object"


def generate_java_models(document: dict[str, Any]) -> list[str]:
    schemas = document.get("components", {}).get("schemas", {})
    if not isinstance(schemas, dict):
        return []
    lines: list[str] = []
    for name in sorted(schemas):
        schema = resolve(document, schemas[name])
        type_name = "Soar" + ts_identifier(name)
        if name == "ApiResult":
            lines.extend([
                "    /** Standard SOCP response envelope. */",
                "    public static final class SoarApiResult<T> {",
                "        public Integer code;",
                "        public String message;",
                "        public T data;",
                "        public String traceId;",
                "        public String timestamp;",
                "    }",
                "",
            ])
            continue
        lines.extend([f"    public static final class {type_name} {{"])
        properties = schema.get("properties", {}) if isinstance(schema, dict) else {}
        if isinstance(properties, dict) and properties:
            for property_name in sorted(properties):
                lines.append(f"        public {java_type(document, properties[property_name])} "
                             f"{java_field_name(property_name)};")
        else:
            lines.append("        public java.util.Map<String, Object> values;")
        lines.extend(["    }", ""])
    return lines


def generate_java_client(document: dict[str, Any]) -> tuple[str, list[Operation]]:
    """Generate a dependency-free Java 21 client from the same operation model.

    This is intentionally a small wire client rather than a second product
    implementation: generated methods preserve paths, parameters and headers,
    while returning the raw JSON body so the smoke runner can assert the
    deployment's real envelope without introducing a JSON runtime dependency.
    """
    operations = extract_operations(document)
    lines = [
        "package com.socp.generated;",
        "",
        "import java.io.IOException;",
        "import java.net.URI;",
        "import java.net.URLEncoder;",
        "import java.net.http.HttpClient;",
        "import java.net.http.HttpRequest;",
        "import java.net.http.HttpResponse;",
        "import java.nio.charset.StandardCharsets;",
        "import java.time.Duration;",
        "import java.util.ArrayList;",
        "import java.util.LinkedHashMap;",
        "import java.util.List;",
        "import java.util.Map;",
        "import java.util.Set;",
        "import java.util.regex.Matcher;",
        "import java.util.regex.Pattern;",
        "",
        "/** Generated by build/verify-openapi-sdk.py; do not edit by hand. */",
        "public final class SoarClient {",
        "    private static final Pattern PATH_PARAMETER = Pattern.compile(\"\\\\{([^}]+)}\");",
        "    private final String baseUrl;",
        "    private final HttpClient httpClient;",
        "    private final Map<String, String> defaultHeaders;",
        "",
        "    public record SoarSdkResponse(int status, Map<String, List<String>> headers, String body) {",
        "        public String header(String name) {",
        "            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {",
        "                if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isEmpty())",
        "                    return entry.getValue().get(0);",
        "            }",
        "            return null;",
        "        }",
        "    }",
        "",
        "    public SoarClient(String baseUrl, String sessionCookie, String tenant) {",
        "        this.baseUrl = baseUrl.replaceAll(\"/+$\", \"\");",
        "        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();",
        "        this.defaultHeaders = new LinkedHashMap<>();",
        "        this.defaultHeaders.put(\"Accept\", \"application/json\");",
        "        if (sessionCookie != null && !sessionCookie.isBlank())",
        "            this.defaultHeaders.put(\"Cookie\", \"SOCP_SESSION=\" + sessionCookie);",
        "        if (tenant != null && !tenant.isBlank()) this.defaultHeaders.put(\"X-Tenant-Id\", tenant);",
        "    }",
        "",
        "    public SoarSdkResponse request(String method, String template, Map<String, Object> params,",
        "                                    Map<String, String> headers, String body, Set<String> pathNames)",
        "            throws IOException, InterruptedException {",
        "        Map<String, Object> values = params == null ? Map.of() : params;",
        "        String rendered = renderPath(template, values, pathNames);",
        "        StringBuilder query = new StringBuilder();",
        "        for (Map.Entry<String, Object> entry : values.entrySet()) {",
        "            if (pathNames.contains(entry.getKey()) || entry.getValue() == null) continue;",
        "            query.append(query.length() == 0 ? '?' : '&')",
        "                    .append(encode(entry.getKey())).append('=')",
        "                    .append(encode(String.valueOf(entry.getValue())));",
        "        }",
        "        HttpRequest.Builder request = HttpRequest.newBuilder()",
        "                .uri(URI.create(baseUrl + rendered + query))",
        "                .timeout(Duration.ofSeconds(30));",
        "        defaultHeaders.forEach(request::header);",
        "        if (headers != null) headers.forEach(request::header);",
        "        if (body == null) request.method(method, HttpRequest.BodyPublishers.noBody());",
        "        else {",
        "            request.header(\"Content-Type\", \"application/json\");",
        "            request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));",
        "        }",
        "        HttpResponse<String> response = httpClient.send(request.build(),",
        "                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));",
        "        return new SoarSdkResponse(response.statusCode(), response.headers().map(), response.body());",
        "    }",
        "",
        "    private static String renderPath(String template, Map<String, Object> values, Set<String> pathNames) {",
        "        Matcher matcher = PATH_PARAMETER.matcher(template);",
        "        StringBuffer result = new StringBuffer();",
        "        while (matcher.find()) {",
        "            String name = matcher.group(1);",
        "            if (!pathNames.contains(name) || values.get(name) == null)",
        "                throw new IllegalArgumentException(\"missing path parameter \" + name);",
        "            matcher.appendReplacement(result, Matcher.quoteReplacement(encode(String.valueOf(values.get(name)))));",
        "        }",
        "        matcher.appendTail(result);",
        "        return result.toString();",
        "    }",
        "",
        "    private static String encode(String value) {",
        "        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace(\"+\", \"%20\");",
        "    }",
        "",
    ]
    for operation in operations:
        path_params = [parameter for parameter in operation.parameters if parameter.location == "path"]
        path_names = ", ".join(json.dumps(parameter.name) for parameter in path_params)
        lines.extend([
            f"    public SoarSdkResponse {operation.name}(Map<String, Object> params, "
            "Map<String, String> headers, String body) throws IOException, InterruptedException {",
            f"        return request({json.dumps(operation.method)}, {json.dumps(operation.path)}, params, headers, body, Set.of({path_names}));",
            "    }",
            "",
        ])
    lines.extend(generate_java_models(document))
    lines.append("}")
    return "\n".join(lines) + "\n", operations


def find_java() -> str | None:
    return shutil.which("java") or shutil.which("java.exe")


def find_javac() -> str | None:
    return shutil.which("javac") or shutil.which("javac.exe")


def compile_java(sources: list[Path], output: Path) -> tuple[bool, str]:
    compiler = find_javac()
    if compiler is None:
        return False, "javac not found; install JDK 21 first"
    output.mkdir(parents=True, exist_ok=True)
    command = [compiler, "--release", "21", "-encoding", "UTF-8", "-d", str(output)]
    command.extend(str(source) for source in sources)
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    detail = (process.stdout + process.stderr).strip()
    return process.returncode == 0, detail[-4000:]


def find_node() -> str | None:
    return shutil.which("node") or shutil.which("node.exe")


def find_tsc() -> tuple[str, list[str]] | None:
    node = find_node()
    if not node:
        return None
    candidates = [
        ROOT / "frontend" / "node_modules" / "typescript" / "lib" / "tsc.js",
        ROOT / "node_modules" / "typescript" / "lib" / "tsc.js",
    ]
    for candidate in candidates:
        if candidate.is_file():
            return node, [str(candidate)]
    executable = shutil.which("tsc") or shutil.which("tsc.cmd")
    if executable:
        return executable, []
    return None


def compile_typescript(source: Path, output: Path, emit: bool = False) -> tuple[bool, str]:
    compiler = find_tsc()
    if compiler is None:
        return False, "TypeScript compiler not found; install frontend dependencies first"
    executable, prefix = compiler
    output.mkdir(parents=True, exist_ok=True)
    command = [executable, *prefix, "--strict", "--target", "ES2022",
               "--module", "NodeNext", "--moduleResolution", "NodeNext",
               "--lib", "ES2022,DOM", "--skipLibCheck"]
    if emit:
        command.extend(["--outDir", str(output)])
    else:
        command.append("--noEmit")
    command.append(str(source))
    process = subprocess.run(command, cwd=ROOT, capture_output=True, text=True)
    detail = (process.stdout + process.stderr).strip()
    return process.returncode == 0, detail[-4000:]


def cookie_from_headers(headers: Any) -> str | None:
    values = headers.get_all("Set-Cookie") if hasattr(headers, "get_all") else []
    for value in values or []:
        jar = http.cookies.SimpleCookie()
        jar.load(value)
        morsel = jar.get("SOCP_SESSION")
        if morsel and morsel.value:
            return morsel.value
    return None


def login_cookie(gateway: str, username: str, password: str, timeout: float = 15) -> str:
    payload = json.dumps({"username": username, "password": password}).encode("utf-8")
    request = urllib.request.Request(gateway.rstrip("/") + "/auth/login", data=payload,
                                     method="POST", headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            token = cookie_from_headers(response.headers)
            if response.status != 200 or not token:
                raise RuntimeError("login did not return SOCP_SESSION")
            return token
    except urllib.error.HTTPError as error:
        raise RuntimeError(f"login failed HTTP {error.code}") from error


def fetch_runtime_document(url: str, cookie: str, tenant: str) -> dict[str, Any]:
    request = urllib.request.Request(url, headers={
        "Cookie": "SOCP_SESSION=" + cookie,
        "X-Tenant-Id": tenant,
        "Origin": "http://localhost:5173",
        "Accept": "application/json",
    })
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            document = json.loads(response.read().decode("utf-8"))
    except (OSError, ValueError) as error:
        raise RuntimeError(f"failed to fetch runtime OpenAPI {url}: {error}") from error
    if not isinstance(document, dict):
        raise RuntimeError("runtime OpenAPI response is not an object")
    return document


def normalize_base(value: str) -> str:
    base = value.strip().rstrip("/")
    for suffix in ("/v3/api-docs", "/swagger-ui.html"):
        if base.endswith(suffix):
            base = base[:-len(suffix)]
    if not base.endswith("/soar-web"):
        base += "/soar-web"
    return base


def smoke_runner(client_source: Path, operations: list[Operation], output: Path) -> Path:
    by_key = {(operation.method, canonical_path(operation.path)): operation for operation in operations}
    def find(method: str, suffix: str) -> Operation:
        key = (method, canonical_path(suffix))
        if key not in by_key:
            raise RuntimeError(f"generated client lacks {method} {suffix}")
        return by_key[key]

    import_operation = find("POST", "/api/playbooks/import")
    version_get = next((operation for operation in operations
                        if operation.method == "GET" and "/api/playbooks/" in operation.path
                        and "/versions/" in operation.path and not operation.path.endswith("/export")), None)
    version_put = next((operation for operation in operations
                        if operation.method == "PUT" and "/api/playbooks/" in operation.path
                        and "/versions/" in operation.path), None)
    archive = find("PATCH", "/api/playbooks/{playbookId}")
    missing = find("GET", "/api/playbooks/{playbookId}")
    if version_get is None or version_put is None:
        raise RuntimeError("generated client lacks version GET/PUT operations")

    method_import = import_operation.name
    method_get = version_get.name
    method_put = version_put.name
    method_archive = archive.name
    method_missing = missing.name
    source_name = client_source.name
    runner = f'''import {{ GeneratedSoarClient }} from "./{source_name.replace('.mts', '.mjs')}";

const runtimeEnv = ((globalThis as any).process?.env ?? {{}}) as Record<string, string | undefined>;
const baseUrl = runtimeEnv.SOAR_OPENAPI_BASE_URL;
const cookie = runtimeEnv.SOAR_OPENAPI_SESSION;
const tenant = runtimeEnv.SOAR_VERIFY_TENANT || "default";
if (!baseUrl || !cookie) throw new Error("runtime smoke requires SOAR_OPENAPI_BASE_URL and SOAR_OPENAPI_SESSION");

const client = new GeneratedSoarClient({{
  baseUrl,
  headers: {{
    Cookie: `SOCP_SESSION=${{cookie}}`,
    "X-Tenant-Id": tenant,
    Origin: "http://localhost:5173",
  }},
}});
const definition = {{
  schemaVersion: "soar.playbook",
  entryNodeId: "start",
  limits: {{ maxNodeExecutions: 20, maxParallelism: 1 }},
  nodes: [
    {{ id: "start", type: "START", name: "Start" }},
    {{ id: "end", type: "END", name: "End", outcome: "SUCCEEDED" }},
  ],
  edges: [{{ from: "start", to: "end" }}],
}};
const suffix = Math.random().toString(36).slice(2, 12);
const imported = await client.{method_import}({{ body: {{
  name: `OpenAPI SDK CI ${{suffix}}`,
  description: "generated client contract smoke",
  tags: ["ci", "openapi"],
  definition,
  layout: {{}},
}} }});
if (imported.status !== 201 || !imported.body || typeof imported.body !== "object")
  throw new Error(`import expected 201, got ${{imported.status}}`);
const importedData = (imported.body as any).data;
const playbookId = importedData?.playbookId;
const version = importedData?.version;
if (!playbookId || !version) throw new Error("import response lacks playbookId/version");

const versionResponse = await client.{method_get}({{ playbookId, version }} as any);
const etag = versionResponse.headers.get("etag");
if (versionResponse.status !== 200 || !etag || !/^W\\/".+"$/.test(etag))
  throw new Error(`version GET must return weak ETag, got ${{versionResponse.status}}/${{etag}}`);

const saved = await client.{method_put}({{ playbookId, version, ifMatch: etag, body: {{ definition, layout: {{}} }} }} as any);
if (saved.status !== 200) throw new Error(`If-Match update expected 200, got ${{saved.status}}`);
const stale = await client.{method_put}({{ playbookId, version, ifMatch: etag, body: {{ definition, layout: {{}} }} }} as any);
const staleBody = stale.body as any;
if (stale.status !== 412 || !staleBody || typeof staleBody.code !== "number"
    || typeof staleBody.message !== "string" || typeof staleBody.timestamp !== "string")
  throw new Error(`stale If-Match expected ApiResult 412, got ${{stale.status}}`);

const notFound = await client.{method_missing}({{ playbookId: `missing-${{suffix}}` }} as any);
const errorBody = notFound.body as any;
if (notFound.status !== 404 || !errorBody || typeof errorBody.code !== "number"
    || typeof errorBody.message !== "string" || typeof errorBody.timestamp !== "string")
  throw new Error(`missing resource expected ApiResult 404, got ${{notFound.status}}`);

const archived = await client.{method_archive}({{ playbookId, body: {{ status: "ARCHIVED" }} }} as any);
if (archived.status !== 200) throw new Error(`archive cleanup expected 200, got ${{archived.status}}`);
console.log(JSON.stringify({{ status: "PASS", imported: 201, version: 200,
  etag: true, ifMatch: 200, staleIfMatch: 412, errorEnvelope: 404, cleanup: 200 }}));
'''
    runner_path = output / "runtime-smoke.mts"
    runner_path.write_text(runner, encoding="utf-8")
    return runner_path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", default=os.environ.get("SOAR_OPENAPI_SPEC", str(DEFAULT_SPEC)))
    parser.add_argument("--runtime-url", default=os.environ.get(
        "SOAR_OPENAPI_BASE_URL", os.environ.get("SOAR_OPENAPI_RUNTIME_URL", "")))
    parser.add_argument("--output", default=os.environ.get("SOAR_OPENAPI_OUTPUT", ".cache/openapi-sdk"))
    parser.add_argument("--require-runtime", action="store_true",
                        default=os.environ.get("SOAR_OPENAPI_REQUIRE_RUNTIME", "false").lower() == "true")
    parser.add_argument("--skip-compile", action="store_true",
                        default=os.environ.get("SOAR_OPENAPI_SKIP_COMPILE", "false").lower() == "true")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    passed: list[str] = []
    failures: list[str] = []
    warnings: list[str] = []
    output = (ROOT / args.output).resolve() if not Path(args.output).is_absolute() else Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    try:
        snapshot = load_yaml(Path(args.spec).resolve())
        errors = validate_document(snapshot, "snapshot")
        if errors:
            failures.extend(errors)
        else:
            passed.append("static OpenAPI document and references")
        client_text, operations = generate_client(snapshot)
        client_path = output / "soar-client.mts"
        client_path.write_text(client_text, encoding="utf-8")
        passed.append(f"generated TypeScript client ({len(operations)} operations)")
        if args.skip_compile:
            warnings.append("TypeScript compilation skipped")
        else:
            compile_dir = output / "compiled"
            ok, detail = compile_typescript(client_path, compile_dir, emit=False)
            if ok:
                passed.append("generated TypeScript client compiles with strict tsc")
            else:
                failures.append("generated TypeScript client compilation: " + (detail or "unknown error"))

        runtime_base = normalize_base(args.runtime_url) if args.runtime_url.strip() else ""
        runtime_document: dict[str, Any] | None = None
        session: str | None = None
        if runtime_base:
            gateway = os.environ.get("SOCP_GATEWAY_URL", runtime_base)
            username = os.environ.get("SOAR_VERIFY_USERNAME", "admin")
            password = os.environ.get("SOAR_VERIFY_PASSWORD", "admin123")
            tenant = os.environ.get("SOAR_VERIFY_TENANT", "default")
            try:
                session = login_cookie(gateway, username, password)
                passed.append("runtime login sets SOCP_SESSION cookie")
                runtime_url = os.environ.get("SOAR_OPENAPI_SPEC_URL", runtime_base + "/v3/api-docs")
                runtime_document = fetch_runtime_document(runtime_url, session, tenant)
                runtime_errors = validate_document(runtime_document, "runtime")
                runtime_errors.extend(compare_runtime(snapshot, runtime_document))
                if runtime_errors:
                    failures.extend(runtime_errors)
                else:
                    passed.append("runtime OpenAPI matches snapshot and auth/concurrency contract")
            except Exception as error:
                failures.append(str(error))
            if session:
                compile_dir = output / "runtime-compiled"
                runner_path = smoke_runner(client_path, operations, output)
                ok, detail = compile_typescript(client_path, compile_dir, emit=True)
                if not ok:
                    failures.append("generated SDK runtime compile: " + (detail or "unknown error"))
                else:
                    # Compile the runner together with the client so imports are
                    # checked by tsc, then execute only the emitted runner.
                    ok, detail = compile_typescript(runner_path, compile_dir, emit=True)
                    if not ok:
                        failures.append("generated SDK smoke runner compile: " + (detail or "unknown error"))
                    else:
                        runner_js = compile_dir / "runtime-smoke.mjs"
                        environment = os.environ.copy()
                        environment.update({
                            "SOAR_OPENAPI_BASE_URL": runtime_base,
                            "SOAR_OPENAPI_SESSION": session,
                            "SOAR_VERIFY_TENANT": tenant,
                        })
                        node = find_node()
                        if not node:
                            failures.append("Node.js is required for generated SDK runtime smoke")
                        else:
                            process = subprocess.run([node, str(runner_js)], cwd=ROOT,
                                                     env=environment, capture_output=True, text=True)
                            detail = (process.stdout + process.stderr).strip()
                            if process.returncode == 0:
                                passed.append("generated SDK runtime smoke: cookie/ApiResult/ETag/If-Match/status")
                            else:
                                failures.append("generated SDK runtime smoke: " + (detail or "unknown error"))
        elif args.require_runtime:
            failures.append("runtime URL is required; set SOAR_OPENAPI_BASE_URL")
        else:
            warnings.append("runtime OpenAPI and generated-client smoke skipped")

        manifest = {
            "schemaVersion": "soar.openapi-sdk-evidence",
            "generatedAt": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "snapshot": str(Path(args.spec).resolve().relative_to(ROOT)),
            "snapshotSha256": document_hash(snapshot),
            "runtimeConfigured": bool(runtime_base),
            "runtimeSha256": document_hash(runtime_document) if runtime_document else None,
            "operations": [operation.name for operation in operations],
            "passed": passed,
            "failed": failures,
            "warnings": warnings,
            "status": "PASS" if not failures else "FAIL",
        }
        temporary = output / "manifest.json.tmp"
        temporary.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        temporary.replace(output / "manifest.json")
    except Exception as error:
        failures.append(str(error))

    for item in passed:
        print(f"[PASS] {item}")
    for item in warnings:
        print(f"[WARN] {item}")
    for item in failures:
        print(f"[FAIL] {item}")
    print(f"Summary: {len(passed)} passed, {len(failures)} failed, {len(warnings)} warnings")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
