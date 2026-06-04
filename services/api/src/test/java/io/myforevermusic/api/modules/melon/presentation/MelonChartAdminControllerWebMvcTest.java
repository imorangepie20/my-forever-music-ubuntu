package io.myforevermusic.api.modules.melon.presentation;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.myforevermusic.api.modules.auth.application.AuthAccountStore;
import io.myforevermusic.api.modules.auth.application.AuthRegisteredAccount;
import io.myforevermusic.api.modules.melon.application.MelonChartService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MelonChartAdminController.class)
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
class MelonChartAdminControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private MelonChartService melonChartService;

    @MockBean
    private AuthAccountStore authAccountStore;

    @Test
    void shouldRunScrapeForAdminUser() throws Exception {
        when(authAccountStore.findByUserId("admin-user")).thenReturn(Optional.of(account(
            "admin-user",
            "jowoosungtidal@gmail.com"
        )));
        when(melonChartService.refresh()).thenReturn(100);

        mockMvc.perform(post("/api/v1/admin/melon/scrape")
                .param("user_id", "admin-user"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("ok"))
            .andExpect(jsonPath("$.track_count").value(100));
    }

    @Test
    void shouldRejectScrapeForNonAdminUser() throws Exception {
        when(authAccountStore.findByUserId("regular-user")).thenReturn(Optional.of(account(
            "regular-user",
            "regular@example.com"
        )));

        mockMvc.perform(post("/api/v1/admin/melon/scrape")
                .param("user_id", "regular-user"))
            .andExpect(status().isForbidden());

        verify(melonChartService, never()).refresh();
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
