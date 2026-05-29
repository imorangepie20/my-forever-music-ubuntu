package io.myforevermusic.api.modules.pms.presentation;

import io.myforevermusic.api.modules.pms.application.PmsPlaylistImportService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/pms/import")
public class PmsPlaylistImportController {

    private final PmsPlaylistImportService pmsPlaylistImportService;

    public PmsPlaylistImportController(PmsPlaylistImportService pmsPlaylistImportService) {
        this.pmsPlaylistImportService = pmsPlaylistImportService;
    }

    @Operation(summary = "Get real provider playlist import bootstrap data for PMS")
    @GetMapping("/bootstrap")
    public PmsPlaylistImportBootstrapResponse getImportBootstrap(
        @RequestParam(name = "user_id") String userId
    ) {
        return pmsPlaylistImportService.getBootstrap(userId);
    }

    @Operation(summary = "Import selected provider playlists into PMS")
    @PostMapping("/playlists")
    public PmsPlaylistImportResponse importPlaylists(@Valid @RequestBody PmsPlaylistImportRequest request) {
        return pmsPlaylistImportService.importPlaylists(request);
    }

    @Operation(summary = "Import every playlist from the user's preferred platform into PMS")
    @PostMapping("/preferred-platform")
    public PmsPlaylistImportResponse importPreferredPlatformPlaylists(
        @Valid @RequestBody PmsPreferredPlatformImportRequest request
    ) {
        return pmsPlaylistImportService.importPreferredPlatformPlaylists(request.userId());
    }
}
