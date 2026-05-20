package io.myforevermusic.api.modules.pms.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

class PmsUserPlaylistTrackRepositoryTest {

    @Test
    void artistLookupQueryShouldAvoidDistinctOrderingAcrossJoinAlias() throws Exception {
        Method method = PmsUserPlaylistTrackRepository.class.getMethod(
            "findDistinctTracksByUserIdAndArtistName",
            String.class,
            String.class,
            org.springframework.data.domain.Pageable.class
        );

        String query = method.getAnnotation(Query.class).value();

        assertThat(query).contains("from PmsUserTrackEntity track");
        assertThat(query).contains("exists");
        assertThat(query).doesNotContain("select distinct link.track");
    }
}
