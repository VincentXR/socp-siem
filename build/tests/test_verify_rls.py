import importlib.util
from pathlib import Path
import sys
import tempfile
import textwrap
import unittest


ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / "build"
sys.path.insert(0, str(BUILD))
SPEC = importlib.util.spec_from_file_location("verify_rls", BUILD / "verify-rls.py")
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


POLICY_SQL = textwrap.dedent("""\
    DO $$
    DECLARE table_row record;
    BEGIN
        FOR table_row IN
            SELECT DISTINCT c.table_schema, c.table_name
            FROM information_schema.columns c
            WHERE c.table_schema = 'public' AND c.column_name = 'tenant_id'
        LOOP
            EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY',
                           table_row.table_schema, table_row.table_name);
            EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY',
                           table_row.table_schema, table_row.table_name);
            EXECUTE format('DROP POLICY IF EXISTS socp_tenant_isolation ON %I.%I',
                           table_row.table_schema, table_row.table_name);
            EXECUTE format($policy$ CREATE POLICY socp_tenant_isolation ON %I.%I
                USING (tenant_id = current_setting('socp.tenant_id', true))
                WITH CHECK (tenant_id = current_setting('socp.tenant_id', true))
            $policy$, table_row.table_schema, table_row.table_name);
        END LOOP;
    END
    $$;
    """)
COMPOSE = textwrap.dedent("""\
    services:
      demo-web:
        image: demo@sha256:abc
        environment:
          SOCP_PG_HOST: postgres
          SOCP_TENANT_RLS_ENABLED: "true"
      redis:
        image: redis:7
    """)
HELM = "  config:\n    SOCP_TENANT_RLS_ENABLED: \"true\"\n"
WRAPPER = "@ConditionalOnProperty(name = \"socp.tenant.rls.enabled\", matchIfMissing = false)\n"
PROD_GUARD = "if (!isEnabled(env, \"socp.tenant.rls.enabled\")) { violations.add(\"rls\"); }\n"
TENANT_ENTITY = """\
    package com.socp.demo.persistence.entity;

    import jakarta.persistence.Entity;
    import jakarta.persistence.Table;

    @Entity
    @Table(name = "t_demo")
    public class DemoEntity {
        private String tenantId;
    }
    """
GLOBAL_ENTITY = """\
    package com.socp.demo.persistence.entity;

    import jakarta.persistence.Entity;
    import jakarta.persistence.Table;

    @Entity
    @Table(name = "t_global")
    public class GlobalEntity {
        private String id;
    }
    """
EXEMPTION = "demo/t_global\tGlobal reference data replicated from the upstream content pack, identical for every tenant.\n"


class TenantRlsGateTest(unittest.TestCase):
    """The gate must catch a tenant surface that drifts away from the RLS policy script."""

    def setUp(self) -> None:
        self._temporary = tempfile.TemporaryDirectory(ignore_cleanup_errors=True)
        self.addCleanup(self._temporary.cleanup)
        self.root = Path(self._temporary.name)
        self.write("infra/postgres/tenant-rls.sql", POLICY_SQL)
        self.write("build/apply-tenant-rls.sh", "psql --file=infra/postgres/tenant-rls.sql\n")
        self.write("infra/docker-compose.prod.yml", COMPOSE)
        self.write("deploy/helm/socp-core/values.yaml", HELM)
        self.write("platform/socp-tenant/src/main/java/com/socp/platform/tenant/persistence/"
                   "TenantRlsDataSourcePostProcessor.java", WRAPPER)
        self.write("platform/socp-auth/src/main/java/com/socp/platform/auth/security/"
                   "ProdGuard.java", PROD_GUARD)
        self.migration("V1__init.sql", """
            CREATE TABLE IF NOT EXISTS t_demo (
                id VARCHAR(64) NOT NULL,
                -- a comment must not glue itself onto the next column definition
                tenant_id VARCHAR(64) NOT NULL,
                CONSTRAINT pk_demo PRIMARY KEY (id)
            );
            CREATE TABLE IF NOT EXISTS t_global (id VARCHAR(64) NOT NULL);
            """)
        self.entity("DemoEntity.java", TENANT_ENTITY)
        self.entity("GlobalEntity.java", GLOBAL_ENTITY)
        self.exemptions(EXEMPTION)

    def write(self, relative: str, text: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(textwrap.dedent(text).lstrip("\n"), encoding="utf-8", newline="\n")
        return path

    def migration(self, name: str, sql: str) -> Path:
        return self.write(f"services/demo/src/main/resources/db/migration/{name}", sql)

    def entity(self, name: str, source: str) -> Path:
        return self.write(f"services/demo/src/main/java/com/socp/demo/persistence/entity/{name}",
                          source)

    def exemptions(self, text: str) -> Path:
        return self.write("build/tenant-rls-exemptions.txt", text)

    def check(self) -> list[str]:
        return MODULE.evaluate(self.root, min_tenant_tables=1, min_compose_services=1).errors

    def test_reviewed_surface_passes(self):
        self.assertEqual([], self.check())

    def test_entity_tenant_column_without_database_column_fails(self):
        # The exact silent degradation this gate exists for: a tenant predicate that only
        # exists in Java, so layer 3 can never backstop it.
        self.entity("DemoEntity.java", TENANT_ENTITY.replace("t_demo", "t_global"))

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("t_global maps a tenant column", errors[0])
        self.assertIn("application-layer only", errors[0])

    def test_new_global_table_without_exemption_fails(self):
        self.migration("V2__second.sql", "CREATE TABLE IF NOT EXISTS t_other (id VARCHAR(64));\n")

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("t_other has no tenant_id and is not registered", errors[0])

    def test_exemption_of_a_tenant_table_fails(self):
        self.exemptions(EXEMPTION + "demo/t_demo\tWrongly exempted although the table is scoped.\n")

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("demo/t_demo is exempted", errors[0])

    def test_stale_exemption_fails(self):
        self.exemptions(EXEMPTION + "demo/t_gone\tTable was dropped, the entry outlived it.\n")

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("demo/t_gone is stale", errors[0])

    def test_short_exemption_reason_fails(self):
        self.exemptions("demo/t_global\tglobal\n")

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("needs a reason long enough", errors[0])

    def test_later_version_can_scope_a_previously_global_table(self):
        self.exemptions("# every table in this database is tenant scoped\n")
        self.entity("GlobalEntity.java", GLOBAL_ENTITY.replace(
            "private String id;", "private String tenantId;"))
        self.migration("V2__tenant_scope.sql",
                       "ALTER TABLE t_global ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);\n"
                       "ALTER TABLE t_global ALTER COLUMN tenant_id SET NOT NULL;\n")

        self.assertEqual([], self.check())

    def test_dropping_the_tenant_column_reopens_the_exemption(self):
        self.migration("V2__unscope.sql", "ALTER TABLE t_demo DROP COLUMN tenant_id;\n")

        errors = self.check()

        self.assertIn("t_demo has no tenant_id and is not registered", " ".join(errors))
        self.assertIn("t_demo maps a tenant column", " ".join(errors))

    def test_unparsed_create_shape_fails_closed(self):
        # A CREATE TABLE the replay cannot classify must never be read as "global".
        self.migration("V2__weird.sql", "CREATE TABLE t_weird AS SELECT 1 AS id;\n")

        errors = self.check()

        self.assertIn("does not understand this shape", " ".join(errors))

    def test_schema_outside_the_policy_discovery_rule_fails(self):
        self.migration("V2__other_schema.sql",
                       "CREATE TABLE IF NOT EXISTS other.t_scoped (tenant_id VARCHAR(64));\n")

        errors = self.check()

        self.assertIn("outside the public schema", " ".join(errors))

    def test_narrowed_policy_script_fails(self):
        self.write("infra/postgres/tenant-rls.sql", POLICY_SQL.replace(
            "FORCE ROW LEVEL SECURITY", "FORCE TABLE LEVEL SECURITY"))

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("force-row-level-security", errors[0])

    def test_implicit_rls_wrapper_fails(self):
        self.write("platform/socp-tenant/src/main/java/com/socp/platform/tenant/persistence/"
                   "TenantRlsDataSourcePostProcessor.java", WRAPPER.replace("false", "true"))

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("must stay opt-in", errors[0])

    def test_compose_service_without_the_rls_flag_fails(self):
        self.write("infra/docker-compose.prod.yml", COMPOSE.replace(
            'SOCP_TENANT_RLS_ENABLED: "true"', 'SOCP_TENANT_RLS_ENABLED: "false"'))

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("does not set SOCP_TENANT_RLS_ENABLED=true", errors[0])

    def test_entity_without_an_explicit_table_name_fails(self):
        self.entity("UnnamedEntity.java", """
            package com.socp.demo.persistence.entity;

            import jakarta.persistence.Entity;

            @Entity
            public class UnnamedEntity {
                private String tenantId;
            }
            """)

        errors = self.check()

        self.assertEqual(1, len(errors))
        self.assertIn("without @Table(name=...)", errors[0])

    def test_shipped_repository_passes(self):
        result = MODULE.evaluate(MODULE.ROOT)

        self.assertEqual([], result.errors)
        self.assertGreaterEqual(result.tenant_tables, MODULE.MIN_EXPECTED_TENANT_TABLES)
        self.assertGreaterEqual(result.exempt_tables, 1)
        self.assertGreaterEqual(result.compose_services, MODULE.MIN_EXPECTED_COMPOSE_DB_SERVICES)


if __name__ == "__main__":
    unittest.main()
