package com.socp.search.config.api;

import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequestValidationTest {

    private static Validator validator;
    private static ValidatorFactory factory;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void logSourceRequestRejectsMissingRequiredFields() {
        LogSourceRequest request = new LogSourceRequest(
                "", null, null, null, null, null, null, true,
                null, null, null, List.of(), null, null, null, null, null,
                List.of(), 1, null, null);

        assertTrue(validator.validate(request).size() >= 3);
    }

    @Test
    void parseRuleRequestValidatesNestedMappingsAndMapsToDomain() {
        ParseRuleRequest request = new ParseRuleRequest(
                "auth", "source-1", "REGEX", "(?<user>\\w+)",
                List.of(new ParseRuleRequest.FieldMapping("user", "user.name", null)),
                List.of(), true, 1);

        assertTrue(validator.validate(request).isEmpty());
        assertEquals("user.name", request.toDomain().mapping().get(0).field());
    }

    @Test
    void sourceRequestKeepsPersistenceMetadataServerOwned() {
        LogSourceRequest request = new LogSourceRequest(
                "auth", SourceType.FILE, ParseFormat.AUTO, "demo/auth.log", null,
                null, "local", true, "beginning", null, null, List.of(), null,
                null, "utf-8", "event_time", "UTC", List.of("team=soc"), 1,
                null, null);

        var source = request.toNewDomain();
        assertTrue(source.id() != null && !source.id().isBlank());
        assertTrue(source.createdAt() != null);
    }
}
