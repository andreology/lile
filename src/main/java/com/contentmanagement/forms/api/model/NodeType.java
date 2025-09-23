package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NodeType {
    GROUP("group"),
    TEXT("text"),
    FIELD("field"),
    TABLE("table"),
    IMAGE("image");

    private final String value;

    NodeType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
