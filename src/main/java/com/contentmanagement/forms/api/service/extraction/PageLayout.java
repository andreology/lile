package com.contentmanagement.forms.api.service.extraction;

import java.util.List;

public record PageLayout(
        int pageIndex,
        double width,
        double height,
        List<DetectedComponent> components
) {
}
