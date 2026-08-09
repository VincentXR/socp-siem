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

    public SearchController(SplEngine engine, SearchStore store) {
        this.engine = engine;
        this.store = store;
    }

    @GetMapping
    public SplEngine.QueryResult search(@RequestParam(value = "q", defaultValue = "") String q) {
        return engine.execute(q, store.all());
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
