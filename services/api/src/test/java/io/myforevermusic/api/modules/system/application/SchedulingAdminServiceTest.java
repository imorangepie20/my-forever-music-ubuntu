package io.myforevermusic.api.modules.system.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.server.ResponseStatusException;

class SchedulingAdminServiceTest {

    @Test
    void shouldSummarizeDailyEmsSchedulers() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount()));
        MockEnvironment environment = new MockEnvironment()
            .withProperty("app.ems.acquisition.user-id", "admin-user")
            .withProperty("app.ems.discovery.user-id", "admin-user");

        SchedulingAdminService service = new SchedulingAdminService(
            authAccountStore,
            environment,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        SchedulingAdminService.SchedulingAdminReport report = service.summarize("admin-user");

        assertThat(report.status()).isEqualTo("ok");
        assertThat(report.schedules()).extracting(SchedulingAdminService.ScheduledServiceStatus::id)
            .containsExactly(
                "ems-acquisition",
                "ems-public-discovery",
                "ems-flo-special",
                "ems-loose-track-playlists",
                "ems-pool-worker",
                "sasrec-auto-train",
                "audio-feature-completion",
                "metadata-apply-accepted-isrcs"
            );
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("ems-acquisition"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.status()).isEqualTo("active");
                assertThat(schedule.fixedDelayMs()).isEqualTo(86_400_000L);
                assertThat(schedule.cadenceLabel()).isEqualTo("daily");
            });
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("ems-public-discovery"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.status()).isEqualTo("active");
                assertThat(schedule.fixedDelayMs()).isEqualTo(86_400_000L);
                assertThat(schedule.cadenceLabel()).isEqualTo("daily");
            });
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("ems-pool-worker"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.status()).isEqualTo("active");
                assertThat(schedule.fixedDelayMs()).isEqualTo(10_000L);
                assertThat(schedule.cadenceLabel()).isEqualTo("every 10 seconds");
            });
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("ems-flo-special"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.status()).isEqualTo("active");
                assertThat(schedule.fixedDelayMs()).isEqualTo(86_400_000L);
                assertThat(schedule.cadenceLabel()).isEqualTo("daily");
            });
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("ems-loose-track-playlists"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.status()).isEqualTo("active");
                assertThat(schedule.fixedDelayMs()).isEqualTo(86_400_000L);
                assertThat(schedule.cadenceLabel()).isEqualTo("daily");
                assertThat(schedule.notes()).anyMatch(note -> note.contains("40 unassigned EMS track"));
            });
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("audio-feature-completion"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.status()).isEqualTo("disabled");
                assertThat(schedule.fixedDelayMs()).isEqualTo(300_000L);
                assertThat(schedule.cadenceLabel()).isEqualTo("every 5 minutes");
            });
    }

    @Test
    void shouldFlagEnabledSchedulerMissingRequiredAdminConfiguration() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(adminAccount()));
        MockEnvironment environment = new MockEnvironment()
            .withProperty("app.ems.acquisition.user-id", "admin-user")
            .withProperty("app.ems.discovery.user-id", "admin-user")
            .withProperty("app.recommendation.metadata.apply-accepted-isrcs.enabled", "true");
        SchedulingAdminService service = new SchedulingAdminService(
            authAccountStore,
            environment,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        SchedulingAdminService.SchedulingAdminReport report = service.summarize("admin-user");

        assertThat(report.status()).isEqualTo("attention");
        assertThat(report.schedules()).filteredOn(schedule -> schedule.id().equals("metadata-apply-accepted-isrcs"))
            .singleElement()
            .satisfies(schedule -> {
                assertThat(schedule.enabled()).isTrue();
                assertThat(schedule.configured()).isFalse();
                assertThat(schedule.status()).isEqualTo("blocked");
            });
    }

    @Test
    void shouldRejectNonAdminUser() {
        AuthAccountStore authAccountStore = mock(AuthAccountStore.class);
        when(authAccountStore.findByUserId("regular-user")).thenReturn(Optional.of(regularAccount()));
        SchedulingAdminService service = new SchedulingAdminService(
            authAccountStore,
            new MockEnvironment(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );

        assertThatThrownBy(() -> service.summarize("regular-user"))
            .isInstanceOf(ResponseStatusException.class)
            .satisfies(exception -> assertThat(((ResponseStatusException) exception).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    private AuthRegisteredAccount adminAccount() {
        return account("admin-user", "jowoosungtidal@gmail.com");
    }

    private AuthRegisteredAccount regularAccount() {
        return account("regular-user", "regular@example.com");
    }

    private AuthRegisteredAccount account(String userId, String email) {
        return new AuthRegisteredAccount(
            userId,
            email,
            email,
            "User",
            "tidal",
            null,
            null,
            false,
            "done",
            Instant.parse("2026-05-01T00:00:00Z"),
            Instant.parse("2026-05-01T00:00:00Z"),
            Instant.parse("2026-05-01T00:00:00Z")
        );
    }
}
