package com.socp.platform.audit.aspect;

import com.socp.platform.audit.model.AuditRecord;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PathVariable;
import java.time.Instant;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AuditDetailsTest {
    public record Command(String status, String assignee, String password, String evidence) { }
    public void update(@PathVariable("id") String id, Command command) { }
    public void create(Map<String, Object> command) { }
    @Test void preservesObjectAndSafeChangeFieldsButNeverFreeTextOrCredentials() throws Exception {
        AuditRecord base = new AuditRecord("tenant", "UPDATE", "alice", "case", "SUCCESS", Instant.now());
        AuditRecord event = AuditDetails.capture(getClass().getMethod("update", String.class, Command.class),
                new Object[]{"case-42", new Command("CLOSED", "bob", "sensitive-password", "raw-secret-evidence")}, null, base);
        assertThat(event.entityId()).isEqualTo("case-42");
        assertThat(event.changeSummary()).contains("CLOSED", "bob", "password", "evidence")
                .doesNotContain("sensitive-password", "raw-secret-evidence");
    }
    @Test void createGetsNewIdentityFromResponseWithoutSerializingResult() throws Exception {
        AuditRecord base = new AuditRecord("tenant", "CREATE", "alice", "rule", "SUCCESS", Instant.now());
        AuditRecord event = AuditDetails.capture(getClass().getMethod("create", Map.class),
                new Object[]{Map.of("name", "Rule", "token", "secret")},
                Map.of("data", Map.of("id", "rule-42", "password", "secret")), base);
        assertThat(event.entityId()).isEqualTo("rule-42");
        assertThat(event.changeSummary()).contains("name", "token").doesNotContain("secret");
    }
}
