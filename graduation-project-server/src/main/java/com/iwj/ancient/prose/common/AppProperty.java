package com.iwj.ancient.prose.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "app.config")
public class AppProperty {
    private String fileLocate;
}
