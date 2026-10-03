package com.socp.search.config.api.controller;

import com.socp.platform.error.web.GlobalExceptionHandler;
import com.socp.platform.tenant.context.TenantContext;
import com.socp.search.config.domain.SinkTarget;
import com.socp.search.config.persistence.store.SinkTargetStore;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.SpringValidatorAdapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SinkTargetControllerTest {
    @Test
    void editingKeepsSourceBindingAndNeverReturnsCredentials() throws Exception {
        TenantContext.set("tenant-a");
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var store = new SinkTargetStore();
            var saved = store.save(SinkTarget.create("Receiver", "HTTP", "https://example.test/ingest", "isolated-test-token", true));
            var mvc = MockMvcBuilders.standaloneSetup(new SinkTargetController(store))
                    .setControllerAdvice(new GlobalExceptionHandler())
                    .setValidator(new SpringValidatorAdapter(factory.getValidator())).build();
            String target = "{\"name\":\"Renamed\",\"type\":\"HTTP\",\"uri\":\"https://example.test/ingest\",\"enabled\":true}";
            mvc.perform(put("/api/v1/outputs/" + saved.id()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credentialAction\":\"KEEP\",\"target\":" + target + "}"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").value(saved.id()))
                    .andExpect(jsonPath("$.data.name").value("Renamed"))
                    .andExpect(jsonPath("$.data.authToken").doesNotExist())
                    .andExpect(jsonPath("$.data.authTokenConfigured").value(true));
            assertEquals("isolated-test-token", store.get(saved.id()).authToken());
            mvc.perform(put("/api/v1/outputs/" + saved.id()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"target\":" + target + "}"))
                    .andExpect(status().isBadRequest());
            for (String protocol : java.util.List.of("OPENSEARCH", "KAFKA", "SEARCH")) {
                mvc.perform(post("/api/v1/outputs").contentType(MediaType.APPLICATION_JSON)
                                .content(target.replace("HTTP", protocol)))
                        .andExpect(status().isBadRequest());
            }
            // Old flat payloads or missing intent must fail closed rather than clearing a saved secret.
            mvc.perform(put("/api/v1/outputs/" + saved.id()).contentType(MediaType.APPLICATION_JSON)
                            .content(target))
                    .andExpect(status().isBadRequest());
            for (String uri : java.util.List.of("https://user:secret@example.test/ingest", "https://example.test/ingest#fragment", "http:///missing-host")) {
                String invalidTarget = target.replace("https://example.test/ingest", uri);
                mvc.perform(put("/api/v1/outputs/" + saved.id()).contentType(MediaType.APPLICATION_JSON)
                                .content("{\"credentialAction\":\"KEEP\",\"target\":" + invalidTarget + "}"))
                        .andExpect(status().isBadRequest());
                mvc.perform(post("/api/v1/outputs/validate").contentType(MediaType.APPLICATION_JSON).content(invalidTarget))
                        .andExpect(status().isBadRequest());
            }
            assertEquals("isolated-test-token", store.get(saved.id()).authToken());
            mvc.perform(post("/api/v1/outputs/validate").contentType(MediaType.APPLICATION_JSON).content(target))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.valid").value(true))
                    .andExpect(jsonPath("$.data.networkTested").value(false)).andExpect(jsonPath("$.data.writesEvent").value(false));
            assertEquals(1, store.list().size());
            TenantContext.set("tenant-b");
            mvc.perform(put("/api/v1/outputs/" + saved.id()).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"credentialAction\":\"CLEAR\",\"target\":" + target + "}"))
                    .andExpect(status().isNotFound());
        } finally {
            TenantContext.clear();
        }
    }
}
