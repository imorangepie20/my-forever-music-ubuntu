package io.myforevermusic.api.modules.recommendation.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Accepted ISRC 후보를 EMS track row 에 주기적으로 적용한다.
 *
 * 기본 disabled. enable 하려면:
 *   app.recommendation.metadata.apply-accepted-isrcs.enabled=true
 *
 * admin-user-id 는 MetadataNormalizationAdminService 의 관리자 검증을 그대로 통과해야 한다.
 */
@org.springframework.context.annotation.Profile("!local")
@Component
public class MetadataNormalizationApplyScheduler {

    private static final Logger log = LoggerFactory.getLogger(MetadataNormalizationApplyScheduler.class);

    private final MetadataNormalizationAdminService adminService;

    @Value("${app.recommendation.metadata.apply-accepted-isrcs.enabled:false}")
    private boolean enabled;

    @Value("${app.recommendation.metadata.apply-accepted-isrcs.admin-user-id:}")
    private String adminUserId;

    @Value("${app.recommendation.metadata.apply-accepted-isrcs.batch-limit:100}")
    private int batchLimit;

    public MetadataNormalizationApplyScheduler(MetadataNormalizationAdminService adminService) {
        this.adminService = adminService;
    }

    @Scheduled(
        fixedDelayString = "${app.recommendation.metadata.apply-accepted-isrcs.fixed-delay-ms:3600000}",
        initialDelayString = "${app.recommendation.metadata.apply-accepted-isrcs.initial-delay-ms:300000}"
    )
    public void run() {
        if (!enabled) {
            return;
        }
        if (adminUserId == null || adminUserId.isBlank()) {
            log.warn("Metadata normalization ISRC apply scheduler skipped because admin-user-id is not configured.");
            return;
        }
        try {
            MetadataNormalizationAdminService.IsrcApplyResult result =
                adminService.applyAcceptedIsrcCandidates(adminUserId, batchLimit);
            log.info(
                "Metadata normalization ISRC apply tick reviewed={} considered={} applied={} no_match={} conflict={}",
                result.reviewedCount(),
                result.isrcConsideredCount(),
                result.applied().size(),
                result.noMatch().size(),
                result.conflicts().size()
            );
        } catch (Exception ex) {
            log.warn("Metadata normalization ISRC apply scheduler failed: {}", ex.getMessage());
        }
    }
}
