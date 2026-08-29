-- Apply after every service has run its Flyway migrations.
-- The application connection wrapper sets socp.tenant_id for each pooled
-- connection.  '*' is reserved for explicit system maintenance only.
DO $$
DECLARE
    table_row record;
BEGIN
    FOR table_row IN
        SELECT DISTINCT c.table_schema, c.table_name
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
          AND c.column_name = 'tenant_id'
    LOOP
        EXECUTE format('ALTER TABLE %I.%I ENABLE ROW LEVEL SECURITY',
                       table_row.table_schema, table_row.table_name);
        EXECUTE format('ALTER TABLE %I.%I FORCE ROW LEVEL SECURITY',
                       table_row.table_schema, table_row.table_name);
        EXECUTE format('DROP POLICY IF EXISTS socp_tenant_isolation ON %I.%I',
                       table_row.table_schema, table_row.table_name);
        EXECUTE format($policy$
            CREATE POLICY socp_tenant_isolation ON %I.%I
            USING (current_setting('socp.tenant_id', true) = '*'
                   OR tenant_id = current_setting('socp.tenant_id', true))
            WITH CHECK (current_setting('socp.tenant_id', true) = '*'
                        OR tenant_id = current_setting('socp.tenant_id', true))
        $policy$, table_row.table_schema, table_row.table_name);
    END LOOP;
END
$$;
