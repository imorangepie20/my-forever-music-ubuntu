package io.myforevermusic.api.modules.recommendation.application;

import java.util.List;

public record AudioTasteMode(
    String modeId,
    String label,
    int trackCount,
    double confidence,
    AudioTasteProfileService.Centroid centroid,
    List<TopArtist> topArtists,
    List<RepresentativeTrack> representativeTracks
) {
    public AudioTasteMode {
        topArtists = topArtists == null ? List.of() : List.copyOf(topArtists);
        representativeTracks = representativeTracks == null ? List.of() : List.copyOf(representativeTracks);
    }

    public record TopArtist(String artistName, int trackCount) {
    }

    public record RepresentativeTrack(
        String trackId,
        String title,
        String artistName,
        String sourcePlatform,
        double distanceToCentroid
    ) {
    }
}
