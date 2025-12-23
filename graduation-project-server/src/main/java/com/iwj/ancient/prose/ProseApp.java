package com.iwj.ancient.prose;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * spring boot 启动类
 *
 * @author avinzhang
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class ProseApp {
    public static void main(String[] args) {
        SpringApplication.run(ProseApp.class, args);
    }
}