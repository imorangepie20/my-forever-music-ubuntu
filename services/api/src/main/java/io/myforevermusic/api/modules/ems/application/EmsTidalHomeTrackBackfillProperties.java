package io.myforevermusic.api.modules.ems.application;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ems.tidal-home-track-backfill")
public class EmsTidalHomeTrackBackfillProperties {

    private boolean enabled = true;
    private int batchSize = 3;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }
}
