import importlib.util
from pathlib import Path
import sys
import unittest


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
        cls.document = MODULE.load_yaml(ROOT / "docs" / "soar-2.0-openapi.yaml")

    def test_snapshot_has_no_unresolved_references_and_required_contract(self):
        self.assertEqual(MODULE.validate_document(self.document), [])

    def test_operation_names_are_unique_and_stable_without_operation_id(self):
        operations = MODULE.extract_operations(self.document)
        names = [operation.name for operation in operations]
        self.assertEqual(len(names), len(set(names)))
        self.assertIn("postApiV2PlaybooksImport", names)
        self.assertIn("putApiV2PlaybooksPlaybookIdVersionsVersion", names)

    def test_generated_client_contains_models_and_all_operations(self):
        source, operations = MODULE.generate_client(self.document)
        self.assertEqual(len(operations), 71)
        self.assertIn("export interface SoarApiResult", source)
        self.assertIn("export class GeneratedSoarV2Client", source)
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


if __name__ == "__main__":
    unittest.main()
