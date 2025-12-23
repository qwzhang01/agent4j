package com.iwj.ancient.prose.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.UnsupportedEncodingException;
import java.util.Enumeration;

/**
 * 日志拦截器
 *
 * @author avinzhang
 */
@Slf4j
public class RequestLogInterceptor implements HandlerInterceptor {
    private static final String REQUEST_TIME = "a901f26174fd45c2b409d7206ca507b6";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws UnsupportedEncodingException {
        // Controller方法调用之前
        long now = System.currentTimeMillis();

        // 请求头信息
        Enumeration<String> headerNames = request.getHeaderNames();
        StringBuilder headerParam = new StringBuilder("{");
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            String headerValue = request.getHeader(headerName);
            headerParam.append(headerName);
            headerParam.append(": ");
            headerParam.append(headerValue);
            headerParam.append(",");
        }
        headerParam.append("}");

        // 请求URL参数
        Enumeration<String> attributeNames = request.getAttributeNames();
        StringBuilder sbParam = new StringBuilder("{");
        while (attributeNames.hasMoreElements()) {
            String paramName = attributeNames.nextElement();
            String paramValue = String.valueOf(request.getAttribute(paramName));
            sbParam.append(paramName);
            sbParam.append(": ");
            sbParam.append(paramValue);
            sbParam.append(",");
        }
        sbParam.append("}");

        // 打印日志
        log.info("Http Request Log {requestTime: {}, requestUrl: {}, requestMethod: {}, header: {}, param: {}}",
                now,
                request.getRequestURI(),
                request.getMethod(),
                headerParam,
                sbParam);

        request.setAttribute(REQUEST_TIME, now);

        return true;
    }

    @Override
    public void postHandle(HttpServletRequest request, HttpServletResponse response, Object handler, ModelAndView modelAndView) {
        // Controller 执行完成 DispatcherServlet 视图渲染之前，即可以操作ModelAndView
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        // DispatcherServlet 视图渲染之后  做清理工作
        long now = System.currentTimeMillis();

        log.info("Http response Log {responseTime: {}, duration: {}毫秒, HttpStatus: {}, responseData: ''}",
                now,
                now - Long.parseLong(String.valueOf(request.getAttribute(REQUEST_TIME))),
                response.getStatus());
    }
}