package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayoutSpec(
        LayoutKind kind,
        LayoutAxis axis,
        Double gap,
        LayoutAlignment align,
        LayoutJustify justify
) {
}
