# agents/vector-pipeline

Vector 日志转发配置（端点 Agent 侧），对接 SOCP 的 SEARCH（采集/检索）服务。

## 定位
- Vector 只负责**采集 + 传输**，不做语义解析。
- 解析、规则、告警、存储、UI 全部在 SOCP（SEARCH/OpenSearch/DETECT/SOAR/REPORT）内完成，保证单一可信解析路径。
- 与 `com.siem` 时期的旁路契约完全一致（json codec + newline_delimited + healthcheck 关闭 + disk buffer + retry 5）。

## 数据流
```
[sources] file / syslog / kafka
   → [transforms.gls_normalize]  规范化元数据（collector_host / ingested_at / parse_format）
      → [sinks.gls_ingest]        NDJSON POST → SEARCH /api/v1/ingest
```

## 运行
```bash
# 1) 先起 SEARCH（search-config 服务，默认 18081）
bash socp/build/run-slice.sh start        # 含 alert-web；search-config 另起：见下方
# 2) 校验
tooling/vector/bin/vector.exe validate --no-environment vector.toml
# 3) 启动采集
tooling/vector/bin/vector.exe --config vector.toml
```

## 与 search-config 的关系
`vector.toml` 可由 `search-config` 服务动态生成：
- `POST /search-config/api/v1/sources` 登记日志源
- `POST /search-config/api/v1/render` 拿回完整 vector.toml
- 多机采集：每台一个 Vector，`.collector_host` 自动区分来源，无需改 SEARCH 侧代码。

## 关键坑（来自 com.siem 迁移经验）
- **必须关 `healthcheck.enabled`**：SEARCH ingest 只收 POST，否则探活 405 让 Vector 启动失败。
- **json + newline_delimited**：用 raw_message 会丢 collector_host 等元数据。
- **disk buffer + retry 5**：SEARCH 重启期间数据落盘，恢复后补发不丢。
- **多行日志**需启用 `multiline`（Java 堆栈等），默认未开。
