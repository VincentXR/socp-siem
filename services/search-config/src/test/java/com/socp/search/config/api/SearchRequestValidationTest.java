package com.socp.search.config.api;

import com.socp.search.config.domain.DataSourceType;
import com.socp.search.config.domain.LogSource;
import com.socp.search.config.domain.ParseFormat;
import com.socp.search.config.domain.SourceType;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @AfterAll
    static void tearDown() {
        validator = null;
    }

    @Test
    void rejectsMissingMetadataFields() {
        assertFalse(validator.validate(new DataSourceType(null, "", "", null, true, null)).isEmpty());
    }

    @Test
    void acceptsAValidLogSourceAndRejectsAnOversizedFrequency() {
        LogSource valid = LogSource.create("auth", SourceType.FILE, ParseFormat.AUTO,
                "/var/log/auth.log", null, null, "prod", true);
        assertTrue(validator.validate(valid).isEmpty());

        LogSource invalid = new LogSource(valid.id(), valid.name(), valid.type(), valid.format(),
                valid.path(), valid.address(), valid.topic(), valid.env(), valid.enabled(),
                valid.readFrom(), valid.multiline(), valid.sinkTargetId(), List.of(), valid.description(),
                valid.protocol(), valid.charset(), valid.timeField(), valid.timezone(), List.of(),
                86401, valid.categoryId(), valid.groupId(), valid.createdAt());
        assertFalse(validator.validate(invalid).isEmpty());
    }
}
