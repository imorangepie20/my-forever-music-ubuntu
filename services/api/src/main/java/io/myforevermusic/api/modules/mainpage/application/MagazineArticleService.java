package io.myforevermusic.api.modules.mainpage.application;

import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionSignalEntity;
import io.myforevermusic.api.modules.ems.infrastructure.persistence.EmsAcquisitionSignalRepository;
import io.myforevermusic.api.modules.mainpage.infrastructure.MagazineArticleEnricher;
import io.myforevermusic.api.modules.mainpage.infrastructure.MagazineArticleEnricher.ArticleMetadata;
import io.myforevermusic.api.modules.mainpage.infrastructure.persistence.MagazineArticleCacheEntity;
import io.myforevermusic.api.modules.mainpage.infrastructure.persistence.MagazineArticleCacheRepository;
import io.myforevermusic.api.modules.mainpage.presentation.MagazineArticleResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@org.springframework.context.annotation.Profile("!local")
@Service
public class MagazineArticleService {

    private static final Logger log = LoggerFactory.getLogger(MagazineArticleService.class);
    private static final int SCAN_MULTIPLIER = 4;
    private static final int MAX_SCAN = 200;

    private final EmsAcquisitionSignalRepository signalRepository;
    private final MagazineArticleCacheRepository cacheRepository;
    private final MagazineArticleEnricher enricher;

    public MagazineArticleService(
        EmsAcquisitionSignalRepository signalRepository,
        MagazineArticleCacheRepository cacheRepository,
        MagazineArticleEnricher enricher
    ) {
        this.signalRepository = signalRepository;
        this.cacheRepository = cacheRepository;
        this.enricher = enricher;
    }

    @Transactional
    public List<MagazineArticleResponse> findRecent(int limit) {
        int safeLimit = Math.max(1, limit);
        int scanLimit = Math.min(MAX_SCAN, Math.max(safeLimit * SCAN_MULTIPLIER, safeLimit));
        List<EmsAcquisitionSignalEntity> rows = signalRepository.findRecentArticles(PageRequest.of(0, scanLimit));

        LinkedHashSet<String> seenUrls = new LinkedHashSet<>();
        List<EmsAcquisitionSignalEntity> uniqueRows = new ArrayList<>();
        for (EmsAcquisitionSignalEntity row : rows) {
            String url = row.getArticleUrl();
            if (url == null || url.isBlank() || !seenUrls.add(url)) {
                continue;
            }
            uniqueRows.add(row);
            if (uniqueRows.size() >= safeLimit) {
                break;
            }
        }

        if (uniqueRows.isEmpty()) {
            return List.of();
        }

        List<String> requestedUrls = uniqueRows.stream().map(EmsAcquisitionSignalEntity::getArticleUrl).toList();
        Map<String, MagazineArticleCacheEntity> cached = new HashMap<>();
        for (MagazineArticleCacheEntity entry : cacheRepository.findAllByArticleUrlIn(requestedUrls)) {
            cached.put(entry.getArticleUrl(), entry);
        }

        List<EmsAcquisitionSignalEntity> rowsToEnrich = new ArrayList<>();
        for (EmsAcquisitionSignalEntity row : uniqueRows) {
            if (!cached.containsKey(row.getArticleUrl())) {
                rowsToEnrich.add(row);
            }
        }
        if (!rowsToEnrich.isEmpty()) {
            List<MagazineArticleCacheEntity> enriched = rowsToEnrich.parallelStream()
                .map(this::enrichRow)
                .toList();
            cacheRepository.saveAll(enriched);
            for (MagazineArticleCacheEntity entry : enriched) {
                cached.put(entry.getArticleUrl(), entry);
            }
        }

        int prunedCount = cacheRepository.deleteByArticleUrlNotIn(requestedUrls);
        if (prunedCount > 0) {
            log.debug("Pruned {} stale magazine_article_cache rows outside the current {} URL window.",
                prunedCount, requestedUrls.size());
        }

        List<MagazineArticleResponse> responses = new ArrayList<>(uniqueRows.size());
        for (EmsAcquisitionSignalEntity row : uniqueRows) {
            MagazineArticleCacheEntity entry = cached.get(row.getArticleUrl());
            if (entry == null) {
                continue;
            }
            responses.add(new MagazineArticleResponse(
                row.getSourceName(),
                row.getSourceUrl(),
                entry.getArticleUrl(),
                entry.getArticleTitle(),
                entry.getArticleTitleKo(),
                entry.getDescription(),
                entry.getDescriptionKo(),
                entry.getRationale(),
                entry.getRationaleKo(),
                entry.getImageUrl(),
                row.getCreatedAt()
            ));
        }
        return responses;
    }

    private MagazineArticleCacheEntity enrichRow(EmsAcquisitionSignalEntity row) {
        String url = row.getArticleUrl();
        String title = row.getArticleTitle();
        String rationale = row.getRationale();
        ArticleMetadata metadata = enricher.fetchMetadata(url);
        String description = metadata.description();
        return new MagazineArticleCacheEntity(
            url,
            row.getSourceName(),
            title,
            enricher.translateToKorean(title),
            description,
            enricher.translateToKorean(description),
            rationale,
            enricher.translateToKorean(rationale),
            metadata.imageUrl(),
            Instant.now()
        );
    }
}
