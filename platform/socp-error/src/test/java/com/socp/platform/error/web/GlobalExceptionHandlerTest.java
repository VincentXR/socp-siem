package com.socp.platform.error.web;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.socp.platform.error.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private ListAppender<ILoggingEvent> logEvents;
    private Level originalLevel;

    @BeforeEach
    void captureHandlerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        originalLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logEvents = new ListAppender<>();
        logEvents.start();
        logger.addAppender(logEvents);
    }

    @AfterEach
    void releaseHandlerLogs() {
        Logger logger = (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
        logger.detachAppender(logEvents);
        logger.setLevel(originalLevel);
        logEvents.stop();
        logEvents.list.clear();
    }

    private List<ILoggingEvent> eventsAt(Level level) {
        return logEvents.list.stream().filter(event -> event.getLevel() == level).toList();
    }

    @Test
    void preservesResponseStatusExceptionHttpCodeAndReason() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "案件标题不能为空"));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().code());
        assertEquals("案件标题不能为空", response.getBody().message());
    }

    @Test
    void replacesMissingResponseStatusReasonWithAnActionableSentence() {
        var response = handler.handleResponseStatus(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals(404, response.getBody().code());
        assertEquals("请求的资源不存在，请确认标识或刷新列表", response.getBody().message());
    }

    @Test
    void keepsFieldValidationDetailInTheBadRequestEnvelope() {
        var target = new Object();
        var binding = new BeanPropertyBindingResult(target, "request");
        binding.addError(new FieldError("request", "code", "code is required"));

        var response = handler.handleValidation(new MethodArgumentNotValidException(null, binding));

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertEquals(400, response.getBody().code());
        assertEquals("code: code is required", response.getBody().message());
        assertNull(response.getBody().data());
    }

    /**
     * 领域文案缺失时信封必须落到「发生了什么 + 下一步」的兜底人话句，且 HTTP 状态
     * 仍按 code 对齐；否则调用方的退避/告警只看得到一段空白 message。
     */
    @Test
    void substitutesAnActionableSentenceForEveryMissingBusinessMessage() {
        Map<Integer, String> expected = new LinkedHashMap<>();
        expected.put(400, "请求参数不合法，请修正后重试");
        expected.put(401, "登录状态已失效，请重新登录");
        expected.put(403, "当前账号无权执行该操作");
        expected.put(404, "请求的资源不存在，请确认标识或刷新列表");
        expected.put(409, "资源状态冲突，请刷新后重试");
        expected.put(413, "请求的数据量过大，请缩小查询范围后重试");
        expected.put(429, "请求过于频繁，请稍后重试");

        for (Map.Entry<Integer, String> fallback : expected.entrySet()) {
            var response = handler.handleApi(new ApiException(fallback.getKey(), null));

            assertEquals(HttpStatus.valueOf(fallback.getKey()), response.getStatusCode(),
                    "code=" + fallback.getKey() + " 必须映射到同名 HTTP 状态");
            assertEquals(fallback.getKey(), response.getBody().code());
            assertEquals(fallback.getValue(), response.getBody().message(),
                    "code=" + fallback.getKey() + " 缺少领域文案时必须给出可行动兜底句");
            assertTrue(eventsAt(Level.ERROR).isEmpty(),
                    "4xx 业务异常不得按 5xx 记 error 日志，code=" + fallback.getKey());
        }
    }

    /** 空白文案与 null 同样视为「没有领域句」，不能把空格串原样发给前端。 */
    @Test
    void treatsABlankBusinessMessageAsMissing() {
        var response = handler.handleApi(new ApiException(409, "   "));

        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        assertEquals("资源状态冲突，请刷新后重试", response.getBody().message());
    }

    /**
     * 没有专属人话句的 4xx（区间内标准码）仍要拿到可行动兜底句，且 HTTP 状态与 code 对齐。
     */
    @Test
    void givesStandardCodesWithoutADedicatedSentenceAFallbackLine() {
        var response = handler.handleApi(new ApiException(405, null));

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertEquals(405, response.getBody().code());
        assertEquals("请求未成功，请稍后重试或联系运维", response.getBody().message());
        assertFalse(logEvents.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR),
                "4xx 兜底句不得进入 5xx 日志路径");
    }

    /**
     * 业务自定义码（≥500 但不落在标准 HTTP 区间）仍按 200 返回，靠 body.code 区分；
     * message 必须换成统一的内部故障语句，绝不把「没有文案」暴露成空串。
     */
    @Test
    void keepsCustomBusinessCodeAtHttpOkWithAGenericSentence() {
        var response = handler.handleApi(new ApiException(10001, null));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(10001, response.getBody().code());
        assertEquals(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE, response.getBody().message());
        assertFalse(logEvents.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR),
                "业务码不是真实 5xx，不得污染 5xx 告警日志");
    }

    /**
     * 5xx 业务异常：原文（驱动/SQL 文本）只能留在日志里，对外固定为统一语句，
     * 并必须留下可被 traceId 关联的 error 记录，否则运维只剩一个「服务处理失败」。
     */
    @Test
    void logsFiveXxBusinessFailureInternallyAndKeepsTheEnvelopeGeneric() {
        var response = handler.handleApi(new ApiException(503, null,
                new IllegalStateException("jdbc connection to db-prod-3:5432 refused")));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertEquals(503, response.getBody().code());
        assertEquals(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE, response.getBody().message());
        assertFalse(response.getBody().message().contains("db-prod-3"), "内部主机不得出现在信封里");

        List<ILoggingEvent> errors = eventsAt(Level.ERROR);
        assertEquals(1, errors.size(), "5xx 业务异常必须落一条 error 日志");
        ILoggingEvent logged = errors.getFirst();
        assertTrue(logged.getFormattedMessage().contains("code=503"),
                "日志必须带上业务码供下钻，实际=" + logged.getFormattedMessage());
        assertNotNull(logged.getThrowableProxy(), "5xx 日志必须保留堆栈，不能只留一行文本");
        assertEquals(ApiException.class.getName(), logged.getThrowableProxy().getClassName(),
                "日志堆栈必须落在业务异常本身，便于定位抛出点");
        IThrowableProxy internalDetail = logged.getThrowableProxy().getCause();
        assertNotNull(internalDetail, "内部原因必须留在日志链路里，对外已被替换成统一语句");
        assertEquals("jdbc connection to db-prod-3:5432 refused", internalDetail.getMessage());
    }

    /**
     * 5xx 且自带运维可读文案时同样要记 error：状态码策略把这类异常当作服务故障，
     * 日志必须与对外文案一起留下，供 traceId 关联。
     */
    @Test
    void logsFiftyXXEvenWhenTheExceptionCarriesItsOwnMessage() {
        var response = handler.handleApi(new ApiException(502, "上游服务未就绪"));

        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode());
        assertEquals("上游服务未就绪", response.getBody().message(), "领域文案优先于兜底句");
        assertEquals(1, eventsAt(Level.ERROR).size());
    }

    /**
     * Controller 显式声明的 5xx：reason 缺失时补统一语句，并把异常堆栈记入 error，
     * 让声明式故障与被吞掉的未处理异常在日志里可区分。
     */
    @Test
    void logsDeclarativeServerErrorAndSubstitutesTheMissingReason() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR));

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertEquals(500, response.getBody().code());
        assertEquals(GlobalExceptionHandler.INTERNAL_ERROR_MESSAGE, response.getBody().message());

        List<ILoggingEvent> errors = eventsAt(Level.ERROR);
        assertEquals(1, errors.size(), "声明式 5xx 必须落一条 error 日志");
        assertTrue(errors.getFirst().getFormattedMessage().contains("code=500"),
                "日志必须带状态码，实际=" + errors.getFirst().getFormattedMessage());
        assertNotNull(errors.getFirst().getThrowableProxy());
    }

    /** 4xx 声明式异常不是服务故障，不得污染 5xx 告警日志。 */
    @Test
    void doesNotLogDeclarativeClientErrors() {
        var response = handler.handleResponseStatus(
                new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE));

        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, response.getStatusCode());
        assertEquals("请求的数据量过大，请缩小查询范围后重试", response.getBody().message());
        assertTrue(eventsAt(Level.ERROR).isEmpty());
    }
}
