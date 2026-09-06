package com.socp.soar.web.persistence.store;

import com.socp.platform.tenant.context.TenantContext;
import com.socp.soar.web.domain.Playbook;
import com.socp.soar.web.domain.PlaybookStatus;
import com.socp.soar.web.persistence.entity.PlaybookEntity;
import com.socp.soar.web.persistence.repository.PlaybookRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** Deterministic store coverage: tenant scoping, CRUD argument passing, seeding and corrupt-row fallbacks. */
@ExtendWith(MockitoExtension.class)
class PlaybookStoreCoverageTest {

    private static final String TENANT = "tenant-a";

    @Mock
    private PlaybookRepository repo;

    @BeforeEach
    void setUp() {
        TenantContext.set(TENANT);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private PlaybookStore store() {
        return new PlaybookStore(repo, false);
    }

    @Test
    void constructorSeedsTheDefaultTenantWhenTheStoreIsEmpty() {
        given(repo.countByTenantId("default")).willReturn(0L);

        new PlaybookStore(repo, true);

        ArgumentCaptor<PlaybookEntity> captor = ArgumentCaptor.forClass(PlaybookEntity.class);
        verify(repo, times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .allSatisfy(entity -> {
                    assertThat(entity.getTenantId()).isEqualTo("default");
                    assertThat(entity.getId()).isNotBlank();
                    assertThat(entity.getCreatedAt()).isNotNull();
                })
                .anySatisfy(entity -> assertThat(entity.isEnabled()).isTrue())
                .anySatisfy(entity -> assertThat(entity.isEnabled()).isFalse());
    }

    @Test
    void constructorSkipsSeedingWhenTheDefaultTenantAlreadyHasPlaybooks() {
        given(repo.countByTenantId("default")).willReturn(2L);

        new PlaybookStore(repo, true);

        verify(repo, never()).save(any(PlaybookEntity.class));
    }

    @Test
    void listReturnsTenantScopedPlaybooks() {
        given(repo.findByTenantId(TENANT)).willReturn(List.of(entity("pb-1", "Triage",
                "[\"block\",\"notify\"]", true, "ACTIVE")));

        List<Playbook> playbooks = store().list();

        assertThat(playbooks).hasSize(1);
        Playbook playbook = playbooks.get(0);
        assertThat(playbook.id()).isEqualTo("pb-1");
        assertThat(playbook.name()).isEqualTo("Triage");
        assertThat(playbook.actions()).containsExactly("block", "notify");
        assertThat(playbook.status()).isEqualTo(PlaybookStatus.ACTIVE);
        assertThat(playbook.enabled()).isTrue();
        verify(repo).findByTenantId(TENANT);
    }

    @Test
    void getReturnsNullWhenThePlaybookIsMissingForTheTenant() {
        given(repo.findByIdAndTenantId("missing", TENANT)).willReturn(Optional.empty());

        assertThat(store().get("missing")).isNull();
    }

    @Test
    void savePersistsATenantScopedEntityAndReturnsTheDomainObject() {
        Playbook playbook = Playbook.create("Isolate host", "manual", List.of("isolate"), true);

        Playbook saved = store().save(playbook);

        assertThat(saved).isSameAs(playbook);
        ArgumentCaptor<PlaybookEntity> captor = ArgumentCaptor.forClass(PlaybookEntity.class);
        verify(repo).save(captor.capture());
        PlaybookEntity entity = captor.getValue();
        assertThat(entity.getTenantId()).isEqualTo(TENANT);
        assertThat(entity.getId()).isEqualTo(playbook.id());
        assertThat(entity.getName()).isEqualTo("Isolate host");
        assertThat(entity.getActions()).contains("isolate");
        assertThat(entity.getStatus()).isEqualTo("ACTIVE");
        assertThat(entity.isEnabled()).isTrue();
    }

    @Test
    void saveFallsBackToDraftStatusAndCurrentTimestampWhenMetadataIsMissing() {
        Playbook playbook = new Playbook("pb-x", "No meta", "trigger", List.of("a"), false, null, null);

        store().save(playbook);

        ArgumentCaptor<PlaybookEntity> captor = ArgumentCaptor.forClass(PlaybookEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PlaybookStatus.DRAFT.name());
        assertThat(captor.getValue().getCreatedAt()).isNotNull();
        assertThat(captor.getValue().getTenantId()).isEqualTo(TENANT);
    }

    @Test
    void deleteReturnsTrueOnlyWhenThePlaybookExistsForTheTenant() {
        PlaybookEntity existing = entity("pb-1", "Triage", "[]", true, "ACTIVE");
        given(repo.findByIdAndTenantId("pb-1", TENANT)).willReturn(Optional.of(existing));
        given(repo.findByIdAndTenantId("missing", TENANT)).willReturn(Optional.empty());

        assertThat(store().delete("pb-1")).isTrue();
        assertThat(store().delete("missing")).isFalse();

        verify(repo, times(1)).delete(any(PlaybookEntity.class));
        verify(repo).delete(existing);
    }

    @Test
    void toggleFlipsTheEnabledFlagAndDerivesTheStatus() {
        PlaybookEntity enabled = entity("pb-1", "On", "[]", true, "ACTIVE");
        PlaybookEntity disabled = entity("pb-2", "Off", "[]", false, "DRAFT");
        given(repo.findByIdAndTenantId("pb-1", TENANT)).willReturn(Optional.of(enabled));
        given(repo.findByIdAndTenantId("pb-2", TENANT)).willReturn(Optional.of(disabled));
        given(repo.findByIdAndTenantId("missing", TENANT)).willReturn(Optional.empty());

        PlaybookStore store = store();

        Playbook toggledOff = store.toggle("pb-1");
        assertThat(toggledOff.enabled()).isFalse();
        assertThat(toggledOff.status()).isEqualTo(PlaybookStatus.DRAFT);
        verify(repo).save(enabled);

        Playbook toggledOn = store.toggle("pb-2");
        assertThat(toggledOn.enabled()).isTrue();
        assertThat(toggledOn.status()).isEqualTo(PlaybookStatus.ACTIVE);
        verify(repo).save(disabled);

        assertThat(store.toggle("missing")).isNull();
    }

    @Test
    void corruptActionJsonAndUnknownStatusFallBackToSafeDefaults() {
        given(repo.findByIdAndTenantId("pb-1", TENANT))
                .willReturn(Optional.of(entity("pb-1", "Broken", "not-json", true, "WEIRD")));
        given(repo.findByIdAndTenantId("pb-2", TENANT))
                .willReturn(Optional.of(entity("pb-2", "Disabled", "not-json", false, "WEIRD")));

        PlaybookStore store = store();

        Playbook enabled = store.get("pb-1");
        assertThat(enabled.actions()).isEmpty();
        assertThat(enabled.status()).isEqualTo(PlaybookStatus.ACTIVE);

        Playbook disabled = store.get("pb-2");
        assertThat(disabled.actions()).isEmpty();
        assertThat(disabled.status()).isEqualTo(PlaybookStatus.DRAFT);
    }

    @Test
    void tenantsWithEnabledPlaybooksEnumeratesTenantIdsWithoutRequestContext() {
        given(repo.findTenantIdsWithEnabledPlaybooks()).willReturn(List.of("default", TENANT));

        assertThat(store().tenantsWithEnabledPlaybooks())
                .containsExactly("default", TENANT);
    }

    private static PlaybookEntity entity(String id, String name, String actions,
                                         boolean enabled, String status) {
        PlaybookEntity entity = new PlaybookEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setTrigger("manual");
        entity.setActions(actions);
        entity.setEnabled(enabled);
        entity.setStatus(status);
        entity.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        entity.setTenantId(TENANT);
        return entity;
    }
}
