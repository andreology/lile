package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.model.FormDocument;
import com.contentmanagement.forms.api.model.ProcessingMode;

public interface FormExtractionStrategy {

    ProcessingMode supportedMode();

    FormDocument extract(FormExtractionContext context);
}
