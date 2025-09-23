package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LayoutKind {
    STACK("stack"),
    ROW("row"),
    GRID("grid"),
    FLOW("flow");

    private final String value;

    LayoutKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
