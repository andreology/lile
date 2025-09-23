package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.model.WidgetType;
import org.opencv.core.Rect;

public record DetectedComponent(
        int index,
        DetectedComponentType type,
        Rect boundingBox,
        String text,
        double confidence,
        WidgetType widgetType
) {
}
