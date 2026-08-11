package com.socp.search.config.search;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * SPL 检索 API：GET /api/v1/search?q=source=auth severity=HIGH | top src_ip 5
 * 以及归档导出：GET /api/v1/search/export?q=...&format=csv|json
 */
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

    private final SplEngine engine;
    private final SearchStore store;
    private final OsEventReader osReader;

    public SearchController(SplEngine engine, SearchStore store, OsEventReader osReader) {
        this.engine = engine;
        this.store = store;
        this.osReader = osReader;
    }

    @GetMapping
    public SplEngine.QueryResult search(@RequestParam(value = "q", defaultValue = "") String q) {
        // OpenSearch 优先（真实检索库）：可用返回 OS 结果；不可达/失败回退本地 H2 + SplEngine。
        // OS 里只有 ingest 之后的实时数据，历史语料（进程启动前的）由本地 SearchStore 兜底，
        // 因此查询两侧并取 total 大者更符合"全量检索"语义。
        SplEngine.QueryResult os = osReader.search(q, 200);
        SplEngine.QueryResult local = engine.execute(q, store.all());
        if (os == null) return local;
        if (os.total() >= local.total()) return os;
        return local;
    }

    /** 归档导出：检索结果下载为 JSON 或 CSV。 */
    @GetMapping("/export")
    public ResponseEntity<String> export(
            @RequestParam(value = "q", defaultValue = "") String q,
            @RequestParam(defaultValue = "json") String format) {
        SplEngine.QueryResult r = engine.execute(q, store.all());
        String fname, ctype, body;
        if ("csv".equalsIgnoreCase(format)) {
            fname = "search.csv";
            ctype = "text/csv; charset=utf-8";
            body = toCsv(r);
        } else {
            fname = "search.json";
            ctype = "application/json";
            body = SearchEventJson.toJson(r.events());
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fname + "\"")
                .contentType(MediaType.parseMediaType(ctype))
                .body(body);
    }

    private static String toCsv(SplEngine.QueryResult r) {
        StringBuilder sb = new StringBuilder("timestamp,source,host,severity,msg\n");
        for (SearchEvent e : r.events()) {
            sb.append(e.timestamp()).append(',').append(csv(e.source())).append(',').append(csv(e.host()))
                    .append(',').append(e.severity()).append(',').append(csv(e.msg())).append('\n');
        }
        return sb.toString();
    }

    private static String csv(String s) {
        if (s == null) return "";
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }
}
