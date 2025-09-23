package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record NodeAssociations(
        String labelFor,
        List<String> describedBy
) {
}
