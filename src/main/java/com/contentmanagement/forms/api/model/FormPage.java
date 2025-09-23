package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record FormPage(
        int index,
        List<String> flowOrder,
        LayoutNode root,
        List<LayoutNode> nodes
) {
}
