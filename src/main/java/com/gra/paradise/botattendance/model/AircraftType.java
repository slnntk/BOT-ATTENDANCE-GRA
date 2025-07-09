package com.gra.paradise.botattendance.model;

import lombok.Getter;

@Getter
public enum AircraftType {
    EC135("EC135"),
    MAVERICK("Maverick"),
    VALKYRE("Valkyre");

    private final String displayName;

    AircraftType(String displayName) {
        this.displayName = displayName;
    }

}