-- Apache AGE 图扩展初始化（需带 AGE 的 PG 镜像，如 bitnine/age:PG18；标准 postgres:18 不含 AGE）
-- 每租户一张图 socp_{tenantId}（§8.2：图 + 关系库同一实例，运维归零）
-- 手动在 AGE 镜像上执行，不在自动 init 中运行。

CREATE EXTENSION IF NOT EXISTS age;

-- 在指定库内为租户建图（重复执行安全）
CREATE OR REPLACE FUNCTION socp_create_tenant_graph(tenant_id text)
RETURNS void AS $$
BEGIN
    EXECUTE format('CREATE GRAPH IF NOT EXISTS socp_%s', tenant_id);
END;
$$ LANGUAGE plpgsql;

-- 示例：默认租户 t1 建图
SELECT socp_create_tenant_graph('t1');

-- 使用图时（示例，业务代码按租户动态拼接 graph 名）：
-- LOAD 'age'; SET search_path = ag_catalog, "$user", public;
-- SELECT * FROM socp_t1.alarm_entity WHERE ... ;
