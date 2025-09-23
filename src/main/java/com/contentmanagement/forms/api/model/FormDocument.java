package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormDocument(FormMeta meta, List<FormPage> pages) {
}
