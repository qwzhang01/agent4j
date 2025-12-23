package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.dto.enums.RightLogicalEnum;
import com.iwj.ancient.prose.kit.JsonKit;
import com.iwj.ancient.prose.kit.RequestKit;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.AsyncHandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 权限抽象类
 *
 * @author avinzhang
 */
@Slf4j
public abstract class AbstractAuthInterceptor implements AsyncHandlerInterceptor {

    protected abstract AccountDto.Info getAccount(String token);

    protected String defaultToken() {
        return "main_token";
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws UnsupportedEncodingException {
        String token = request.getHeader("Access-Token");
        if (StringUtils.isBlank(token)) {
            token = defaultToken();
        }
        if (StringUtils.isBlank(token)) {
            this.print(response, R.unLogin());
            return false;
        }
        token = URLDecoder.decode(token, StandardCharsets.UTF_8.toString());
        if (token.startsWith("Bearer")) {
            token = token.substring(6).trim();
        }
        AccountDto.Info account = getAccount(token);
        if (account == null) {
            this.print(response, R.unLogin());
            return false;
        }
        List<String> roles = account.getRoles();
        if (roles == null || roles.size() == 0) {
            this.print(response, R.unAuth());
            return false;
        }
        List<String> rightItemCodes = account.getRightItemCodes();
        if (rightItemCodes == null || rightItemCodes.size() == 0) {
            this.print(response, R.unAuth());
            return false;
        }

        if (handler instanceof HandlerMethod) {
            HandlerMethod h = (HandlerMethod) handler;
            if (h.hasMethodAnnotation(Auth.class)) {
                Auth auth = h.getMethodAnnotation(Auth.class);
                // 方法允许的权限编码数组
                String[] methodPermission = auth.permission();
                // 方法允许的权限编码数组内逻辑关系，或和且两个关系
                // 或  即用户拥有方法允许的权限编码数组任意一个即可访问
                // 且  即用户拥有方法允许的权限编码数组所有权限才可访问
                RightLogicalEnum logical = auth.logical();
                // 用户拥有的权限编码数组
                if (!hasPermission(logical, methodPermission, rightItemCodes)) {
                    this.print(response, R.unAuth());
                    return false;
                }
            }
        }

        AccountDto.Context userInfoDto = new AccountDto.Context();
        userInfoDto.setId(account.getId());
        userInfoDto.setToken(token);
        userInfoDto.setIp(RequestKit.getIpAddress(request));

        UserContext.setCurrentUser(userInfoDto);

        return true;
    }

    /**
     * 判断用户是否有访问权限
     *
     * @param logical          "and" "or" 关系，形容methodPermission注解内容
     * @param methodPermission 注解权限code数组
     * @param userPermission   用户权限数组
     * @return
     */
    boolean hasPermission(RightLogicalEnum logical, String[] methodPermission, List<String> userPermission) {
        if (RightLogicalEnum.AND.equals(logical)) {
            // 且,methodPermission数组中所有元素都要在userPermission中存在，返回true
            for (String code : methodPermission) {
                if (!userPermission.contains(code)) {
                    return false;
                }
            }
            return true;
        } else {
            // 或,methodPermission数组中任意元素在userPermission中存在，返回true
            for (String code : methodPermission) {
                if (userPermission.contains(code)) {
                    return true;
                }
            }
            return false;
        }
    }


    /**
     * 输出JSON到前端
     *
     * @param response     HttpServletResponse
     * @param commonResult CommonResult
     */
    void print(HttpServletResponse response, R<?> commonResult) {
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json;charset=utf-8");
        try (PrintWriter writer = response.getWriter()) {
            writer.print(JsonKit.obj2String(commonResult));
        } catch (IOException e) {
            log.error("越权访问输出报错", e);
        }
    }


    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        UserContext.remove();
    }
}