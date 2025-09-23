package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Geometry(BoundingBox bbox) {
}
