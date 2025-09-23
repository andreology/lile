package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeStyle(
        String fontFamily,
        Double fontSize,
        String weight,
        String color,
        Double leading,
        Double tracking
) {
    public static final NodeStyle DEFAULT = new NodeStyle(null, null, null, null, null, null);
}
