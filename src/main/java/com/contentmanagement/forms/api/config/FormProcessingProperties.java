package com.contentmanagement.forms.api.config;

import com.contentmanagement.forms.api.model.ProcessingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "form.processing")
public class FormProcessingProperties {

    private ProcessingMode defaultMode = ProcessingMode.OPENCV_ONLY;
    private List<String> fallbackImageResources = new ArrayList<>(List.of("IMG_1060.png", "IMG_1062.png"));
    private String baseUnit = "px";
    private boolean ocrEnabled = true;
    private String ocrLanguage = "eng";
    private String tessDataPath = "classpath:tessdata";

    public ProcessingMode getDefaultMode() {
        return defaultMode;
    }

    public void setDefaultMode(ProcessingMode defaultMode) {
        this.defaultMode = defaultMode;
    }

    public List<String> getFallbackImageResources() {
        return fallbackImageResources;
    }

    public void setFallbackImageResources(List<String> fallbackImageResources) {
        this.fallbackImageResources = fallbackImageResources;
    }

    public String getBaseUnit() {
        return baseUnit;
    }

    public void setBaseUnit(String baseUnit) {
        this.baseUnit = baseUnit;
    }

    public boolean isOcrEnabled() {
        return ocrEnabled;
    }

    public void setOcrEnabled(boolean ocrEnabled) {
        this.ocrEnabled = ocrEnabled;
    }

    public String getOcrLanguage() {
        return ocrLanguage;
    }

    public void setOcrLanguage(String ocrLanguage) {
        this.ocrLanguage = ocrLanguage;
    }

    public String getTessDataPath() {
        return tessDataPath;
    }

    public void setTessDataPath(String tessDataPath) {
        this.tessDataPath = tessDataPath;
    }
}
