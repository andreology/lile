package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum LayoutAxis {
    X("x"),
    Y("y"),
    Z("z");

    private final String value;

    LayoutAxis(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
