package com.pulsbreath.desktop;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Окремий запис сирих і відфільтрованих значень для дослідницької таблиці.
 */
public final class RawRecord {

    private final long timestamp;

    public final double rawPulse;
    public final double filteredPulse;
    public final double rawBreath;
    public final double filteredBreath;
    public final double rawDistance;

    public RawRecord(
            long timestamp,
            double rawPulse,
            double filteredPulse,
            double rawBreath,
            double filteredBreath,
            double rawDistance
    ) {
        this.timestamp = timestamp;
        this.rawPulse = rawPulse;
        this.filteredPulse = filteredPulse;
        this.rawBreath = rawBreath;
        this.filteredBreath = filteredBreath;
        this.rawDistance = rawDistance;
    }

    public String getFormattedTime() {
        return DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }

    public double getRawPulse() {
        return rawPulse;
    }

    public double getFilteredPulse() {
        return filteredPulse;
    }

    public double getRawBreath() {
        return rawBreath;
    }

    public double getFilteredBreath() {
        return filteredBreath;
    }

    public double getRawDistance() {
        return rawDistance;
    }
}