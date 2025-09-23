package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.model.BoundingBox;
import com.contentmanagement.forms.api.model.FormDocument;
import com.contentmanagement.forms.api.model.FormMeta;
import com.contentmanagement.forms.api.model.FormPage;
import com.contentmanagement.forms.api.model.Geometry;
import com.contentmanagement.forms.api.model.LayoutAxis;
import com.contentmanagement.forms.api.model.LayoutJustify;
import com.contentmanagement.forms.api.model.LayoutAlignment;
import com.contentmanagement.forms.api.model.LayoutKind;
import com.contentmanagement.forms.api.model.LayoutNode;
import com.contentmanagement.forms.api.model.LayoutSpec;
import com.contentmanagement.forms.api.model.NodeRole;
import com.contentmanagement.forms.api.model.NodeType;
import com.contentmanagement.forms.api.model.PageSize;
import com.contentmanagement.forms.api.model.WidgetSpec;
import com.contentmanagement.forms.api.model.WidgetType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class FormDocumentAssembler {

    private static final String SCHEMA_VERSION = "2.0";

    public FormDocument assemble(List<PageLayout> pageLayouts, String baseUnit) {
        List<FormPage> pages = new ArrayList<>();
        for (PageLayout layout : pageLayouts) {
            pages.add(toFormPage(layout));
        }
        FormMeta meta = buildMeta(pageLayouts, baseUnit);
        return new FormDocument(meta, pages);
    }

    private FormMeta buildMeta(List<PageLayout> pageLayouts, String baseUnit) {
        String normalizedUnit = (baseUnit == null || baseUnit.isBlank()) ? "px" : baseUnit;
        if (pageLayouts.isEmpty()) {
            return new FormMeta(SCHEMA_VERSION, null, null, null, normalizedUnit, null, 0);
        }
        PageLayout first = pageLayouts.get(0);
        PageSize pageSize = new PageSize(first.width(), first.height());
        String title = resolveDocumentTitle(pageLayouts);
        return new FormMeta(SCHEMA_VERSION, title, null, null, normalizedUnit, pageSize, pageLayouts.size());
    }

    private FormPage toFormPage(PageLayout layout) {
        List<String> flowOrder = new ArrayList<>();
        List<LayoutNode> nodes = new ArrayList<>();
        String rootId = "page-" + layout.pageIndex() + "-root";

        for (DetectedComponent component : layout.components()) {
            String nodeId = buildNodeId(layout.pageIndex(), component.index());
            flowOrder.add(nodeId);
            nodes.add(toLayoutNode(component, nodeId));
        }

        LayoutNode root = new LayoutNode(
                rootId,
                NodeType.GROUP,
                NodeRole.PAGE,
                new LayoutSpec(LayoutKind.STACK, LayoutAxis.Y, 12.0, LayoutAlignment.STRETCH, LayoutJustify.START),
                new Geometry(new BoundingBox(0, 0, layout.width(), layout.height())),
                null,
                null,
                null,
                null,
                new ArrayList<>(flowOrder),
                null
        );

        nodes.add(root);
        return new FormPage(layout.pageIndex(), flowOrder, root, nodes);
    }

    private LayoutNode toLayoutNode(DetectedComponent component, String nodeId) {
        NodeType nodeType = mapNodeType(component.type());
        WidgetSpec widgetSpec = mapWidget(component.widgetType());
        return new LayoutNode(
                nodeId,
                nodeType,
                NodeRole.UNKNOWN,
                null,
                new Geometry(new BoundingBox(component.boundingBox().x, component.boundingBox().y, component.boundingBox().width, component.boundingBox().height)),
                null,
                normalize(component.text()),
                null,
                widgetSpec,
                null,
                null
        );
    }

    private String resolveDocumentTitle(List<PageLayout> pageLayouts) {
        return pageLayouts.stream()
                .flatMap(layout -> layout.components().stream()
                        .filter(component -> component.type() == DetectedComponentType.TEXT)
                        .filter(component -> component.text() != null && !component.text().isBlank()))
                .sorted(Comparator.comparingInt(component -> component.boundingBox().y))
                .map(component -> normalize(component.text()))
                .filter(title -> title != null && !title.isBlank())
                .findFirst()
                .orElse(null);
    }

    private String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed.replaceAll("\s+", " ");
    }

    private WidgetSpec mapWidget(WidgetType widgetType) {
        if (widgetType == null) {
            return null;
        }
        return new WidgetSpec(widgetType, null, null, null);
    }

    private NodeType mapNodeType(DetectedComponentType type) {
        return switch (type) {
            case TEXT -> NodeType.TEXT;
            case FIELD -> NodeType.FIELD;
            case TABLE -> NodeType.TABLE;
            case IMAGE -> NodeType.IMAGE;
            case GROUP -> NodeType.GROUP;
        };
    }

    private String buildNodeId(int pageIndex, int componentIndex) {
        return "page-" + pageIndex + "-node-" + componentIndex;
    }
}
