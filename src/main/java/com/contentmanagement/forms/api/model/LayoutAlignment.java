package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LayoutAlignment {
    START("start"),
    CENTER("center"),
    END("end"),
    STRETCH("stretch"),
    BASELINE("baseline");

    private final String value;

    LayoutAlignment(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
