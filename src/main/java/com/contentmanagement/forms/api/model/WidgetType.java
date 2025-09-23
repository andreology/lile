package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum WidgetType {
    TEXT("text"),
    TEXTAREA("textarea"),
    SELECT("select"),
    CHECKBOX("checkbox"),
    RADIO("radio"),
    DATE("date"),
    NUMBER("number"),
    SIGNATURE("signature");

    private final String value;

    WidgetType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
