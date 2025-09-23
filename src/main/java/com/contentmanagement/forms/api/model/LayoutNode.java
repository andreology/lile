package com.contentmanagement.forms.api.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LayoutNode(
        String id,
        NodeType type,
        NodeRole role,
        LayoutSpec layout,
        Geometry geom,
        String legend,
        String text,
        NodeStyle style,
        WidgetSpec widget,
        List<String> children,
        NodeAssociations associations
) {
}
