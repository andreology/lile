package com.contentmanagement.forms.api.service.extraction;

import com.contentmanagement.forms.api.model.ProcessingMode;
import org.springframework.web.multipart.MultipartFile;

public record FormExtractionContext(MultipartFile sourceFile, ProcessingMode mode) {
}
