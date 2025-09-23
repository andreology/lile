package com.contentmanagement.forms.api;

import com.contentmanagement.forms.api.config.FormProcessingProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(FormProcessingProperties.class)
public class ContentManagementFormsApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(ContentManagementFormsApiApplication.class, args);
    }
}
