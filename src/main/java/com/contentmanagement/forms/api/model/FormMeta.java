package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormMeta(
        String schemaVersion,
        String title,
        String publisher,
        String edition,
        String baseUnit,
        PageSize pageSize,
        Integer pages
) {
}
