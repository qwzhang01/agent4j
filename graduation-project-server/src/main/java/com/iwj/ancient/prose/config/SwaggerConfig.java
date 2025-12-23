package com.iwj.ancient.prose.config;

import com.github.xiaoymin.swaggerbootstrapui.annotations.EnableSwaggerBootstrapUI;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

@Configuration
@EnableSwaggerBootstrapUI
@EnableSwagger2
public class SwaggerConfig {

    @Bean
    public Docket createRestApi() {
        String name = this.getClass().getPackage().getName();
        name = name.replace("config", "controller");
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage(name))
                .paths(PathSelectors.any())
                .build();
    }

    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("Alvin-demo 接口文档")
                .description("Alvin-demo 后端接口文档")
                .termsOfServiceUrl("Terms of service")
                .contact(new Contact("Alvin Zhang", "https://github.com/qwzhang01", "782264826@qq.com"))
                .version("API V1.0")
                .build();
    }
}