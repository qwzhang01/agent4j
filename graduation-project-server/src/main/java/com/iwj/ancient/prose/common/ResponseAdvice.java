package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.kit.JsonKit;
import org.springframework.core.MethodParameter;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.lang.Nullable;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import javax.validation.constraints.NotNull;
import java.io.File;

/**
 * 返回结果包装器
 *
 * @author avinzhang
 */
@ControllerAdvice(annotations = RestController.class)
public class ResponseAdvice implements ResponseBodyAdvice<Object> {

    @Override
    public boolean supports(MethodParameter returnType, @NotNull Class<? extends HttpMessageConverter<?>> converterType) {
        if (returnType.getParameterType().equals(R.class)) {
            // 控制器如果包装过则不再包装
            return false;
        }
        // 不是文件下载
        boolean notFileDownload = returnType.getParameterType().equals(InputStreamResource.class) ||
                returnType.getParameterType().equals(Resource.class) ||
                returnType.getParameterType().equals(File.class);
        if (notFileDownload) {
            return false;
        }

        return true;
    }

    @Override
    public Object beforeBodyWrite(@Nullable Object body,
                                  @NotNull MethodParameter returnType,
                                  @NotNull MediaType selectedContentType,
                                  @NotNull Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  @NotNull ServerHttpRequest request,
                                  @NotNull ServerHttpResponse response) {

        if (body instanceof String) {
            return JsonKit.obj2String(R.ok(body));
        }
        return R.ok(body);
    }
}