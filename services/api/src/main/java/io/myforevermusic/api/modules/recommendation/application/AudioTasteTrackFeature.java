package io.myforevermusic.api.modules.recommendation.application;

public record AudioTasteTrackFeature(
    String trackScope,
    String trackId,
    String title,
    String artistName,
    String sourcePlatform,
    String audioFeatureSource,
    boolean audioFeaturesFilled,
    double featureWeight,
    String featureTier,
    Double acousticness,
    Double danceability,
    Double energy,
    Double instrumentalness,
    Double liveness,
    Double speechiness,
    Double tempo,
    Double valence
) {
    public boolean hasRequiredFeatures() {
        return acousticness != null
            && danceability != null
            && energy != null
            && instrumentalness != null
            && liveness != null
            && speechiness != null
            && tempo != null
            && valence != null;
    }

    public boolean usable() {
        return featureWeight > 0.0d && hasRequiredFeatures();
    }
}
