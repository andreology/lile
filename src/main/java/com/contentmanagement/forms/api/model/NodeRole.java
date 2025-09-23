package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonValue;

public enum NodeRole {
    PAGE("page"),
    HEADER("header"),
    SECTION("section"),
    LABEL("label"),
    HEADING("heading"),
    PARAGRAPH("paragraph"),
    FIELDSET("fieldset"),
    FOOTER("footer"),
    NOTE("note"),
    BODY("body"),
    UNKNOWN("unknown");

    private final String value;

    NodeRole(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }
}
