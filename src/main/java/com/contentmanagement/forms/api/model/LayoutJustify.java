package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LayoutJustify {
    START("start"),
    CENTER("center"),
    END("end"),
    SPACE_BETWEEN("space-between"),
    SPACE_AROUND("space-around"),
    SPACE_EVENLY("space-evenly");

    private final String value;

    LayoutJustify(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
