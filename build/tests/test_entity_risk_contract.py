import importlib.util
from pathlib import Path
import sys
import unittest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "build"))
SPEC = importlib.util.spec_from_file_location("risk_contract_gate", ROOT / "build/verify-contracts.py")
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
JAVA = ROOT / "services/detect-web/src/main/java/com/socp/detect/web"


class EntityRiskContractTest(unittest.TestCase):
    def setUp(self):
        self.controller = (JAVA / "api/controller/UebaController.java").read_text(encoding="utf-8")
        self.store = (JAVA / "service/EntityRiskStore.java").read_text(encoding="utf-8")
        self.repository = (JAVA / "persistence/repository/EntityRiskProfileRepository.java").read_text(encoding="utf-8")

    def test_current_database_read_contract(self):
        self.assertEqual([], MODULE.entity_risk_read_findings(self.controller, self.store, self.repository))

    def test_rejects_missing_limit_tenant_filter_and_unbounded_summary(self):
        for store, repository in [
            (self.store, self.repository.replace("limit :limit", "")),
            (self.store, self.repository.replace("where tenant_id=:tenantId", "", 1)),
            (self.store + "\nprofiles.findByTenantId(tenant());", self.repository),
            (self.store, self.repository.replace("count(*) as entities", "entity_key as entities")),
        ]:
            with self.subTest(store=store[-70:], repository=repository[-70:]):
                self.assertTrue(MODULE.entity_risk_read_findings(self.controller, store, repository))


if __name__ == "__main__":
    unittest.main()
