package com.socp.detect.web.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socp.detect.web.persistence.store.WatchlistStore;
import com.socp.detect.web.service.EntityRiskStore;
import com.socp.platform.error.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UebaController.class)
@AutoConfigureMockMvc(addFilters = false)
@TestPropertySource(properties = "socp.security.dev-bypass=true")
class WatchlistCreateContractTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @MockitoBean private WatchlistStore watchlists;
    @MockitoBean private EntityRiskStore risks;

    @Test
    void createReturnsCommittedMembersAndSurfacesConflictWithoutReplacement() throws Exception {
        when(watchlists.create("accounts", List.of("alice")))
                .thenReturn(Map.of("name", "accounts", "size", 1, "values", List.of("alice")))
                .thenThrow(ApiException.of(409, "watchlist already exists"));
        String body = json.writeValueAsString(Map.of("name", "accounts", "values", List.of("alice")));
        mvc.perform(post("/api/v1/watchlists").header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.values[0]").value("alice"));
        mvc.perform(post("/api/v1/watchlists").header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(409));
        org.mockito.Mockito.verify(watchlists, org.mockito.Mockito.never())
                .put(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList());
    }

    @Test
    void invalidNamesMembersAndMissingValuesAreRejectedBeforeMutation() throws Exception {
        for (Object body : List.of(
                Map.of("name", " ", "values", List.of()),
                Map.of("name", "a".repeat(256), "values", List.of()),
                Map.of("name", "accounts/team", "values", List.of()),
                Map.of("name", "accounts\\team", "values", List.of()),
                Map.of("name", "..", "values", List.of()),
                Map.of("name", "%2f", "values", List.of()),
                Map.of("name", "accounts"),
                Map.of("name", "accounts", "values", java.util.Collections.singletonList(null)),
                Map.of("name", "accounts", "values", List.of("a".repeat(257))),
                Map.of("name", "accounts", "values", java.util.Collections.nCopies(10001, "alice")))) {
            mvc.perform(post("/api/v1/watchlists").header("Authorization", "Bearer test-token")
                            .header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(body)))
                    .andExpect(status().isBadRequest());
        }
        verifyNoInteractions(watchlists);
    }

    @Test
    void unicodeNamesCanBeCreatedWithoutEncodingPathSeparators() throws Exception {
        when(watchlists.create("特权_accounts-1.0", List.of("alice")))
                .thenReturn(Map.of("name", "特权_accounts-1.0", "size", 1, "values", List.of("alice")));
        mvc.perform(post("/api/v1/watchlists").header("Authorization", "Bearer test-token")
                        .header("X-Role", "analyst").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "特权_accounts-1.0", "values", List.of("alice")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.name").value("特权_accounts-1.0"));
    }

    @Test
    void viewerCannotCreateWatchlists() throws Exception {
        mvc.perform(post("/api/v1/watchlists").header("Authorization", "Bearer test-token")
                        .header("X-Role", "viewer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"accounts\",\"values\":[\"alice\"]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(watchlists);
    }
}
