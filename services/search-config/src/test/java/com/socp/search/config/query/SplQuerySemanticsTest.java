package com.socp.search.config.query;

import com.socp.search.config.domain.SearchEvent;
import com.socp.search.config.service.SplEngine;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SplQuerySemanticsTest {

    private final SplParser parser = new SplParser();
    private final OpenSearchQueryCompiler compiler = new OpenSearchQueryCompiler();
    private final LocalQueryExecutor executor = new LocalQueryExecutor();

    @Test
    void parsesFilterPipelineAndImplicitAnd() {
        SearchQueryAst ast = parser.parse("source=auth severity>=HIGH | sort timestamp desc | limit 20");

        assertThat(ast.filter()).isInstanceOf(FilterExpression.And.class);
        assertThat(ast.pipeline()).containsExactly(
                new PipelineCommand.Sort("timestamp", PipelineCommand.SortOrder.DESC),
                new PipelineCommand.Limit(20));
    }

    @Test
    void parsesOrAndParenthesesWithContains() {
        SearchQueryAst ast = parser.parse("(msg contains \"failed password\" OR source=auth) AND host=web-1");

        assertThat(ast.filter()).isInstanceOf(FilterExpression.And.class);
        assertThat(((FilterExpression.And) ast.filter()).terms()).hasSize(2);
    }

    @Test
    void parsesEverySupportedPipelineCommand() {
        assertThat(parser.parse("* | count by source").pipeline())
                .containsExactly(new PipelineCommand.CountBy("source"));
        assertThat(parser.parse("* | timechart").pipeline())
                .containsExactly(new PipelineCommand.Timechart());
        assertThat(parser.parse("* | sort timestamp desc | head 3").pipeline()).containsExactly(
                new PipelineCommand.Sort("timestamp", PipelineCommand.SortOrder.DESC),
                new PipelineCommand.Head(3));
    }

    @Test
    void rejectsMalformedSyntaxWithPosition() {
        assertThatThrownBy(() -> parser.parse("source="))
                .isInstanceOf(SplParseException.class)
                .hasMessageContaining("position");
    }

    @Test
    void rejectsUnsupportedPipelineInsteadOfSilentlyChangingMeaning() {
        assertThatThrownBy(() -> parser.parse("source=auth | stats count by source"))
                .isInstanceOf(SplParseException.class)
                .hasMessageContaining("unsupported pipeline");
    }

    @Test
    void compilerAlwaysAddsTenantFilterAndUsesTypedDsl() {
        SearchQueryAst ast = parser.parse("source=auth severity>=HIGH | count by src_ip");

        var compiled = compiler.compile(ast, "tenant-a");
        String dsl = compiled.toString();

        assertThat(dsl).contains("tenantId", "fields.tenant_id.keyword", "\"severity\"", "count_by", "terms");
        assertThat(dsl).doesNotContain("query_string");
        assertThat(dsl).contains("tenant-a");
        assertThat(compiled.path("query").path("bool").path("filter").get(0).has("bool")).isTrue();
        assertThat(compiled.path("query").path("bool").path("must").has("bool")).isTrue();
        assertThat(compiler.compile(parser.parse("*"), "tenant-a")
                .path("query").path("bool").path("must").has("match_all")).isTrue();
    }

    @Test
    void compilerCannotBeAskedToBuildAnUnscopedQuery() {
        assertThatThrownBy(() -> compiler.compile(parser.parse("*"), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tenant");
    }

    @Test
    void localExecutorProducesNonEmptyTopStatistics() {
        List<SearchEvent> events = List.of(
                event("1", "10.0.0.1", "HIGH"), event("2", "10.0.0.1", "INFO"),
                event("3", "10.0.0.2", "CRITICAL"));

        SplEngine.QueryResult result = executor.execute(
                parser.parse("* | top src_ip 2").withPage(10, null), events);

        assertThat(result.stat()).isNotNull();
        assertThat(result.stat().rows()).containsExactly(
                Map.of("key", "10.0.0.1", "count", 2L),
                Map.of("key", "10.0.0.2", "count", 1L));
    }

    @Test
    void localExecutorSupportsCursorPagingWithoutDuplicates() {
        List<SearchEvent> events = List.of(event("1", "10.0.0.1", "INFO"),
                event("2", "10.0.0.2", "INFO"), event("3", "10.0.0.3", "INFO"));
        SearchQueryAst first = parser.parse("*").withPage(2, null);
        SplEngine.QueryResult pageOne = executor.execute(first, events);
        SplEngine.QueryResult pageTwo = executor.execute(first.withPage(2, pageOne.nextCursor()), events);

        assertThat(pageOne.events()).extracting(SearchEvent::eventId).containsExactly("1", "2");
        assertThat(pageTwo.events()).extracting(SearchEvent::eventId).containsExactly("3");
    }

    @Test
    void localExecutorProducesTimechartRows() {
        List<SearchEvent> events = List.of(
                eventAt("1", "2026-08-01T01:00:00Z"), eventAt("2", "2026-08-01T02:00:00Z"),
                eventAt("3", "2026-08-02T01:00:00Z"));

        SplEngine.QueryResult result = executor.execute(parser.parse("* | timechart"), events);

        assertThat(result.stat().rows()).containsExactly(
                Map.of("key", "2026-08-01", "count", 2L),
                Map.of("key", "2026-08-02", "count", 1L));
    }

    @Test
    void localExecutorProducesAFullResultTimelineWithBoundedBuckets() {
        List<SearchEvent> events = new ArrayList<>();
        for (int i = 0; i < 181; i++) {
            events.add(eventAt("timeline-" + i,
                    Instant.parse("2026-01-01T00:00:00Z").plusSeconds(i * 86_400L).toString()));
        }

        SplEngine.QueryResult result = executor.execute(parser.parse("*").withPage(1, null), events, true);

        assertThat(result.events()).hasSize(1);
        assertThat(result.timeline()).hasSize(180);
        assertThat(result.timelineApproximate()).isTrue();
        assertThat(result.timeline().getFirst()).containsEntry("key", "2026-01-02");
        assertThat(result.timeline().getLast()).containsEntry("key", "2026-06-30");
    }

    @Test
    void fieldMappingKeepsCanonicalKeywordFieldsUntouched() {
        assertThat(OpenSearchQueryCompiler.fieldPath("source", true)).isEqualTo("source.ci");
        assertThat(OpenSearchQueryCompiler.fieldPath("severity", true)).isEqualTo("severity");
        assertThat(OpenSearchQueryCompiler.fieldPath("src_ip", true)).isEqualTo("fields.src_ip");
    }

    @Test
    void parserCoversEmptyInputEscapesAndValidationBoundaries() {
        assertThat(parser.parse(null).filter()).isSameAs(FilterExpression.MatchAll.INSTANCE);
        assertThat(parser.parse("   ").pipeline()).isEmpty();
        assertThat(parser.parse("msg contains \"failed \\\"password\\\"\"").filter())
                .isInstanceOf(FilterExpression.Comparison.class);
        assertThatThrownBy(() -> parser.parse("source=auth | head 0"))
                .isInstanceOf(SplParseException.class);
        assertThatThrownBy(() -> parser.parse("source=auth | top src_ip nope"))
                .isInstanceOf(SplParseException.class);
        assertThatThrownBy(() -> parser.parse("source=auth !="))
                .isInstanceOf(SplParseException.class);
        assertThatThrownBy(() -> parser.parse("(source=auth"))
                .isInstanceOf(SplParseException.class);
        assertThatThrownBy(() -> parser.parse("source=auth | unknown"))
                .isInstanceOf(SplParseException.class);
    }

    @Test
    void compilerCoversAllComparisonOperatorsRangesAndAggregations() {
        SearchQueryAst ast = parser.parse(
                "eventId=e1 source!=auth msg contains \"failed\" timestamp>=2026-08-01T00:00:00Z "
                        + "severity<HIGH | sort source asc | head 4")
                .withPage(0 + 20, null);
        String dsl = compiler.compile(ast, "tenant-a", 20).toString();
        assertThat(dsl).contains("must_not", "wildcard", "range").doesNotContain("query_string");
        assertThat(compiler.compile(parser.parse("* | top src_ip 3"), "tenant-a").toString())
                .contains("top", "\"order\":{\"_count\":\"desc\"}");
        assertThat(compiler.compile(parser.parse("* | count by source"), "tenant-a").toString())
                .contains("\"size\":1000", "source.ci");
        assertThat(compiler.compile(parser.parse("* | timechart"), "tenant-a").toString())
                .contains("\"min_doc_count\":1");
        assertThat(compiler.compile(parser.parse("*"), "tenant-a", 10, true).toString())
                .contains("timeline", "date_histogram", "bucket_sort", "\"size\":181");

        SearchQueryAst cursorBase = parser.parse("*");
        SearchQueryAst cursorQuery = cursorBase.withPage(10,
                QueryCursorCodec.encode(cursorBase, event("cursor", "10.0.0.1", "INFO")));
        assertThat(compiler.compile(cursorQuery, "tenant-a").path("search_after")).isNotEmpty();
        assertThatCode(() -> compiler.compile(parser.parse("*"), "tenant-a", 1))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> OpenSearchQueryCompiler.fieldPath("bad field", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void semanticAnalyzerRejectsInvalidFieldTypesAndPipelineShapes() {
        assertThatThrownBy(() -> parser.parse("severity>=urgent"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("invalid severity", "position");
        assertThatThrownBy(() -> parser.parse("timestamp>=yesterday"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("invalid date");
        assertThatThrownBy(() -> parser.parse("count>=many"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("invalid integer");
        assertThatThrownBy(() -> parser.parse("unknown_field>1"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("does not support range");
        assertThatThrownBy(() -> parser.parse("source contains auth"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("does not support contains");
        assertThatThrownBy(() -> parser.parse("* | top unknown_field"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("not aggregatable");
        assertThatThrownBy(() -> parser.parse("* | sort msg"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("not sortable");
        assertThatThrownBy(() -> parser.parse("* | top src_ip | count by source"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("one terminal aggregation");
        assertThatThrownBy(() -> parser.parse("* | count by source | limit 10"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("cannot be combined");
        assertThatThrownBy(() -> parser.parse("* | count by source | sort source"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("final pipeline command");
        assertThatCode(() -> parser.parse("custom_field=value"))
                .doesNotThrowAnyException();
    }

    @Test
    void semanticAnalyzerEnforcesResultAndCursorBoundsAtExecutionBoundaries() {
        assertThatThrownBy(() -> compiler.compile(parser.parse("*").withPage(5_001, null), "tenant-a"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("page size");
        SearchQueryAst cursorBase = parser.parse("*");
        String cursor = QueryCursorCodec.encode(cursorBase, event("cursor", "10.0.0.1", "INFO"));
        assertThatThrownBy(() -> executor.execute(
                parser.parse("* | sort timestamp").withPage(10, cursor), List.of()))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("cursor paging");
        assertThatThrownBy(() -> parser.parse("* | head 5001"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("head limit");
        assertThatThrownBy(() -> parser.parse("* | top src_ip 1001"))
                .isInstanceOf(SplSemanticException.class)
                .hasMessageContaining("top limit");
    }

    @Test
    void localExecutorCoversFiltersSortingLimitsAndEmptyCorpus() {
        List<SearchEvent> events = List.of(
                event("1", "10.0.0.1", "HIGH"),
                new SearchEvent("2", Instant.parse("2026-08-01T00:01:00Z"), "web", "h2", "INFO", "raw",
                        Map.of("tenant_id", "tenant-a", "count", "12", "src_ip", "10.0.0.2"), Map.of()),
                new SearchEvent("3", Instant.parse("2026-08-01T00:02:00Z"), "auth", "h3", "INFO", "raw",
                        Map.of("tenant_id", "tenant-a", "count", "2", "src_ip", "10.0.0.3"), Map.of()));
        assertThat(executor.execute(parser.parse("source=auth severity>=HIGH"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("1");
        assertThat(executor.execute(parser.parse("count>=10 | sort count asc | head 1"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("2");
        assertThat(executor.execute(parser.parse("source!=missing | count by source"), events).stat().rows())
                .isNotEmpty();
        assertThat(executor.execute(parser.parse("*"), null).events()).isEmpty();
        assertThatCode(() -> executor.execute(parser.parse("*"), events).events())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> QueryCursorCodec.decode("not-a-cursor", parser.parse("*")))
                .isInstanceOf(SplParseException.class);
    }

    @Test
    void alignsTypedFiltersCasePolicyMissingBucketsAndDefaultSort() {
        List<SearchEvent> events = List.of(
                new SearchEvent("B", Instant.parse("2026-08-02T00:00:00Z"), "AUTH", "h1", "HIGH",
                        "User *BLOCKED?", Map.of("src_ip", "10.0.0.10", "count", "10"), Map.of()),
                new SearchEvent("A", Instant.parse("2026-08-02T00:00:00Z"), "auth", "h2", "LOW",
                        "allowed", Map.of("src_ip", "10.0.0.2", "count", "2"), Map.of()),
                new SearchEvent("C", Instant.parse("2026-08-01T00:00:00Z"), "dns", "h3", "CRITICAL",
                        "blocked", Map.of(), Map.of()));

        assertThat(executor.execute(parser.parse("source=auth"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("A", "B");
        assertThat(executor.execute(parser.parse("eventId=a"), events).events()).isEmpty();
        assertThat(executor.execute(parser.parse("msg contains \"*blocked?\""), events).events())
                .extracting(SearchEvent::eventId).containsExactly("B");
        assertThat(executor.execute(parser.parse("timestamp>=2026-08-02T00:00:00Z"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("A", "B");
        assertThat(executor.execute(parser.parse("count>2"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("B");
        assertThat(executor.execute(parser.parse("src_ip>10.0.0.2"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("B");
        assertThat(executor.execute(parser.parse("severity>=HIGH"), events).events())
                .extracting(SearchEvent::eventId).containsExactly("B", "C");
        assertThat(executor.execute(parser.parse("* | count by src_ip"), events).stat().rows())
                .extracting(row -> row.get("key")).doesNotContain("null");

        String containsDsl = compiler.compile(parser.parse("msg contains \"*blocked?\""), "tenant-a").toString();
        assertThat(containsDsl).contains("msg.exact", "case_insensitive", "\\\\*blocked\\\\?");
        assertThat(compiler.compile(parser.parse("*"), "tenant-a").path("sort").toString())
                .contains("timestamp", "eventId").doesNotContain("_id");
    }

    @Test
    void bindsCursorToQueryAndRejectsTampering() {
        SearchQueryAst sourceQuery = parser.parse("source=auth");
        String cursor = QueryCursorCodec.encode(sourceQuery, event("cursor", "10.0.0.1", "INFO"));

        assertThatThrownBy(() -> executor.execute(
                parser.parse("source=dns").withPage(10, cursor), List.of()))
                .isInstanceOf(SplParseException.class)
                .hasMessageContaining("cursor");
        String tampered = cursor.substring(0, cursor.length() - 1)
                + (cursor.endsWith("A") ? "B" : "A");
        assertThatThrownBy(() -> QueryCursorCodec.decode(tampered, sourceQuery))
                .isInstanceOf(SplParseException.class);
    }

    @Test
    void countByCapsBucketsAndReportsTruncation() {
        List<SearchEvent> events = new ArrayList<>();
        for (int i = 0; i < 1_005; i++) {
            events.add(new SearchEvent("e-" + i, Instant.parse("2026-08-01T00:00:00Z"),
                    "s-" + i, "host", "INFO", "event", Map.of(), Map.of()));
        }

        SplEngine.QueryResult.Stat stat = executor.execute(parser.parse("* | count by source"), events).stat();

        assertThat(stat.rows()).hasSize(1_000);
        assertThat(stat.approximate()).isTrue();
        assertThat(stat.sumOtherDocCount()).isEqualTo(5);
    }

    private static SearchEvent event(String id, String ip, String severity) {
        return new SearchEvent(id, Instant.parse("2026-08-01T00:00:00Z"), "auth", "host", severity,
                "failed password", Map.of("tenant_id", "tenant-a", "src_ip", ip), Map.of());
    }

    private static SearchEvent eventAt(String id, String timestamp) {
        return new SearchEvent(id, Instant.parse(timestamp), "auth", "host", "INFO", "event",
                Map.of("tenant_id", "tenant-a"), Map.of());
    }
}
