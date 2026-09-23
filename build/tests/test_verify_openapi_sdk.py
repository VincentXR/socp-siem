import importlib.util
import argparse
import contextlib
import copy
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "verify_openapi_sdk", ROOT / "build" / "verify-openapi-sdk.py"
)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class VerifyOpenApiSdkTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.document = MODULE.load_yaml(ROOT / "docs" / "soar-openapi.yaml")

    def test_snapshot_has_no_unresolved_references_and_required_contract(self):
        self.assertEqual(MODULE.validate_document(self.document), [])

    def test_operation_names_are_unique_and_stable_without_operation_id(self):
        operations = MODULE.extract_operations(self.document)
        names = [operation.name for operation in operations]
        self.assertEqual(len(names), len(set(names)))
        self.assertIn("postApiPlaybooksImport", names)
        self.assertIn("putApiPlaybooksPlaybookIdVersionsVersion", names)

    def test_generated_client_contains_models_and_all_operations(self):
        source, operations = MODULE.generate_client(self.document)
        self.assertEqual(len(operations), 73)
        self.assertIn("async getHealth(", source)
        self.assertIn("async postApiPlaybooksPlaybookIdVersionsVersionRollback(", source)
        self.assertIn("export interface SoarApiResult", source)
        self.assertIn("export class GeneratedSoarClient", source)
        self.assertIn("credentials: 'include'", source)
        self.assertIn("Cookie", source)
        self.assertIn("If-Match", source)

    def test_runtime_comparison_normalizes_path_variable_names(self):
        runtime = {
            "openapi": "3.1.0",
            "components": {
                "securitySchemes": {
                    "cookieAuth": {"type": "apiKey", "in": "cookie", "name": "SOCP_SESSION"},
                    "tenantHeader": {"type": "apiKey", "in": "header", "name": "X-Tenant-Id"},
                },
                "responses": {
                    "ApiResult": {"description": "ok", "content": {"application/json": {"schema": {}}}},
                    "VersionResult": {"description": "version", "headers": {"ETag": {"schema": {"type": "string"}}}},
                    "Error": {"description": "error", "content": {"application/json": {"schema": {}}}},
                },
            },
            "paths": {},
        }
        for path, method, operation in MODULE.iter_operations(self.document):
            copied = dict(operation)
            copied["parameters"] = [
                MODULE.resolve(self.document, item)
                for item in operation.get("parameters", [])
                if isinstance(MODULE.resolve(self.document, item), dict)
            ]
            runtime["paths"].setdefault(path.replace("{playbookId}", "{id}").replace("{version}", "{revision}"), {})[method] = copied
        # The fixture only proves placeholder normalization; the stricter
        # response/header checks are exercised against the real deployment.
        self.assertFalse(any("runtime OpenAPI misses snapshot operations" in error
                             for error in MODULE.compare_runtime(self.document, runtime)))

    def test_normalize_base_accepts_service_context_and_spec_suffix(self):
        self.assertEqual(
            MODULE.normalize_base("http://localhost:18092/soar-web/v3/api-docs"),
            "http://localhost:18092/soar-web",
        )
        self.assertEqual(
            MODULE.normalize_base("http://localhost:18083"),
            "http://localhost:18083/soar-web",
        )

    def test_operation_parameters_override_path_defaults_instead_of_being_discarded(self):
        inherited = {"name": "limit", "in": "query", "required": False, "schema": {"type": "integer"}}
        replacement = {"name": "limit", "in": "query", "required": True, "schema": {"type": "string"}}
        document = {"paths": {"/items": {"parameters": [inherited]}}}
        parameters = MODULE.operation_parameters(document, "/items", {"parameters": [replacement]})
        self.assertEqual(len(parameters), 1)
        self.assertTrue(parameters[0].required)
        self.assertEqual(parameters[0].schema, {"type": "string"})

    def test_only_header_names_are_case_insensitive(self):
        inherited = [{"name": "ETag", "in": "header", "schema": {"type": "string"}}]
        overrides = [{"name": "etag", "in": "header", "required": True, "schema": {"type": "string"}}]
        document = {"paths": {"/items": {"parameters": inherited}}}
        for location in ("query", "path", "cookie"):
            overrides.extend({"name": name, "in": location, "schema": {"type": "string"}}
                             for name in ("id", "ID"))
        parameters = MODULE.operation_parameters(document, "/items", {"parameters": overrides})
        self.assertEqual([(value.name, value.location) for value in parameters],
                         [("etag", "header"), ("id", "query"), ("ID", "query"),
                          ("id", "path"), ("ID", "path"), ("id", "cookie"), ("ID", "cookie")])
        self.assertTrue(parameters[0].required)

    def test_duplicates_and_malformed_parameters_fail_instead_of_disappearing(self):
        parameter = {"name": "id", "in": "query", "schema": {"type": "string"}}
        for parameters in ([parameter, parameter], [None], "id", [{**parameter, "required": "false"}]):
            with self.subTest(parameters=parameters), self.assertRaises(ValueError):
                MODULE.operation_parameters({"paths": {}}, "/items", {"parameters": parameters})

    def test_ambiguous_flat_client_parameter_names_are_rejected(self):
        document = copy.deepcopy(self.document)
        document["paths"]["/items"] = {"get": {"parameters": [
            {"name": name, "in": "query", "schema": {"type": "string"}}
            for name in ("query-id", "query_id")
        ], "responses": {"200": {"description": "ok"}}}}
        self.assertTrue(any("ambiguous generated parameter queryId" in error
                            for error in MODULE.validate_document(document)))

    def test_bearer_auth_declaration_is_checked_as_documented(self):
        document = copy.deepcopy(self.document)
        del document["components"]["securitySchemes"]["bearerAuth"]
        self.assertTrue(any("bearerAuth" in error for error in MODULE.validate_document(document)))

    def test_cookie_parameters_fail_instead_of_becoming_unrelated_headers(self):
        document = copy.deepcopy(self.document)
        document["paths"]["/cookie-parameter"] = {"get": {"parameters": [
            {"name": "session", "in": "cookie", "schema": {"type": "string"}},
        ], "responses": {"200": {"description": "ok"}}}}
        self.assertTrue(any("explicit cookie parameters are unsupported" in error
                            for error in MODULE.validate_document(document)))
        with self.assertRaisesRegex(ValueError, "cookie parameters"):
            MODULE.generate_client(document)

    def test_model_names_cannot_silently_merge_after_normalization(self):
        document = copy.deepcopy(self.document)
        document["components"]["schemas"].update({
            "shared-model": {"type": "object"}, "shared_model": {"type": "object"},
        })
        self.assertTrue(any("ambiguous generated model SoarSharedModel" in error
                            for error in MODULE.validate_document(document)))
        with self.assertRaisesRegex(ValueError, "ambiguous generated model"):
            MODULE.generate_models(document)

    def test_alias_cycles_fail_with_a_reference_error(self):
        document = {"components": {"schemas": {
            "A": {"$ref": "#/components/schemas/B"},
            "B": {"$ref": "#/components/schemas/A"},
        }}}
        for operation in (
                lambda: MODULE.resolve(document, {"$ref": "#/components/schemas/A"}),
                lambda: MODULE.generate_models(document)):
            with self.assertRaisesRegex(ValueError, "circular OpenAPI reference alias"):
                operation()

    def test_schema_types_keep_all_union_members_null_and_boolean_schemas(self):
        self.assertEqual(MODULE.ts_type({}, {"type": ["string", "integer", "null"]}),
                         "string | number | null")
        self.assertEqual(MODULE.ts_type({}, {"type": "null"}), "null")
        self.assertEqual(MODULE.ts_type({}, False), "never")
        self.assertEqual(MODULE.ts_type({}, True), "unknown")

    def test_schema_references_use_names_only_for_whole_components(self):
        document = {"components": {"schemas": {
            "Foo/Bar": {"type": "object", "properties": {"value": {"type": "integer"}}},
        }}}
        self.assertEqual(MODULE.ts_type(document, {"$ref": "#/components/schemas/Foo~1Bar"}),
                         "SoarFooBar")
        self.assertEqual(MODULE.ts_type(document, {
            "$ref": "#/components/schemas/Foo~1Bar/properties/value",
        }), "number")

    def test_recursive_and_composed_models_enforce_types_under_strict_compilation(self):
        if MODULE.find_tsc() is None:
            self.skipTest("install the frontend TypeScript compiler to run generated-model compilation")
        node_ref = {"$ref": "#/components/schemas/Node"}
        document = {"components": {"schemas": {
            "Node": {"type": "object", "required": ["value"], "properties": {
                "value": {"type": ["string", "integer", "null"]},
                "next": {"anyOf": [node_ref, {"type": "null"}]},
                "children": {"type": "array", "items": node_ref},
            }},
            "Alias": node_ref,
            "Tagged": {**node_ref, "properties": {"tag": {"type": "string"}}, "required": ["tag"]},
            "Nullable": {"type": "object", "nullable": True, "properties": {
                "nullishLabel": {"type": "string"},
            }},
            "Overlap": {"allOf": [
                {"anyOf": [{"type": "string"}, {"type": "number"}]},
                {"anyOf": [{"type": "number"}, {"type": "boolean"}]},
            ]},
            "Impossible": False,
            "NullableChoice": {"nullable": True, "oneOf": [{"type": "string"}, {"type": "number"}]},
        }}}
        source = "\n".join(MODULE.generate_models(document)) + """
const tree: SoarNode = { value: 12, next: { value: null, children: [{ value: 'leaf' }] } };
const alias: SoarAlias = tree;
const tagged: SoarTagged = { ...tree, tag: 'root' };
const nullable: SoarNullable = null;
const overlap: SoarOverlap = 1;
const nullableChoice: SoarNullableChoice = null;
// @ts-expect-error a boolean is not a permitted value
const invalidValue: SoarNode = { value: true };
// @ts-expect-error recursive children must also satisfy Node
const invalidChild: SoarNode = { value: 1, children: [{ value: false }] };
// @ts-expect-error the reference sibling adds a required field
const missingTag: SoarTagged = tree;
// @ts-expect-error intersection must preserve each union's parentheses
const invalidOverlap: SoarOverlap = 'text';
// @ts-expect-error a false schema admits no value
const impossible: SoarImpossible = null;
"""
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "models.ts"
            path.write_text(source, encoding="utf-8")
            passed, detail = MODULE.compile_typescript(path, Path(directory) / "compiled")
            self.assertTrue(passed, detail)

    def test_exception_replaces_old_pass_evidence_and_skips_generation_and_runtime(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory)
            for invalid_snapshot in (None, {}, self.document):
                with self.subTest(snapshot=invalid_snapshot):
                    snapshot = output / "invalid.yaml"
                    if invalid_snapshot is not None:
                        snapshot.write_text(json.dumps(invalid_snapshot), encoding="utf-8")
                    manifest = output / "manifest.json"
                    manifest.write_text('{"status":"PASS","stale":true}', encoding="utf-8")
                    args = argparse.Namespace(spec=str(snapshot), output=str(output), skip_compile=True,
                                              runtime_url="http://127.0.0.1:9", require_runtime=True)
                    with mock.patch.object(MODULE, "parse_args", return_value=args), \
                            mock.patch.object(MODULE, "generate_client", side_effect=RuntimeError("generation failed")) as generate, \
                            mock.patch.object(MODULE, "login_cookie") as login, \
                            contextlib.redirect_stdout(io.StringIO()):
                        self.assertEqual(MODULE.main(), 1)
                    if invalid_snapshot is self.document:
                        generate.assert_called_once()
                    else:
                        generate.assert_not_called()
                    login.assert_not_called()
                    evidence = json.loads(manifest.read_text(encoding="utf-8"))
                    self.assertEqual(evidence["status"], "FAIL")
                    self.assertTrue(evidence["failed"])
                    self.assertNotIn("stale", evidence)


if __name__ == "__main__":
    unittest.main()
