package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.entity.LogAction;
import com.iwj.ancient.prose.kit.RequestKit;
import com.iwj.ancient.prose.service.AccountService;
import com.iwj.ancient.prose.service.LogActionService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Component
@Aspect
@Slf4j
public class ActionAspect {

    @Lazy
    @Autowired
    private LogActionService logActionService;

    @Lazy
    @Autowired
    private AccountService accountService;

    @Pointcut("@annotation(com.iwj.ancient.prose.common.ActionDesc)")
    public void actionPoint() {
    }

    @Around("actionPoint()")
    public Object actionAround(ProceedingJoinPoint pjp) throws Throwable {
        long startTimeMillis = System.currentTimeMillis();

        LogAction logAction = new LogAction();
        logAction.setActionTime(LocalDateTime.now());


        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        logAction.setClientIp(UserContext.getIp());

        String userAgent = request.getHeader("user-agent");
        if (userAgent.length() > 255) {
            userAgent = userAgent.substring(0, 255);
        }
        logAction.setUserAgent(userAgent);

        String url = request.getRequestURI();
        if (url.length() > 255) {
            url = url.substring(0, 255);
        }
        logAction.setUrl(url);

        MethodSignature signature = (MethodSignature) pjp.getSignature();
        Method method = signature.getMethod();
        ActionDesc annotation = method.getAnnotation(ActionDesc.class);
        boolean actionWrite = false;
        if (annotation != null) {
            actionWrite = true;
            logAction.setActionDesc(annotation.desc());
            // 获取类上的注解
            annotation = pjp.getTarget().getClass().getAnnotation(ActionDesc.class);
            if (annotation == null) {
                // 获取接口上的注解
                for (Class<?> cls : pjp.getClass().getInterfaces()) {
                    annotation = cls.getAnnotation(ActionDesc.class);
                    if (annotation != null) {
                        break;
                    }
                }
            }
            if (annotation != null) {
                String module = annotation.desc();
                if (module.length() > 255) {
                    module = module.substring(0, 255);
                }
                logAction.setModule(module);
            }

            String token = request.getHeader("Access-Token");
            if (StringUtils.isNotBlank(token)) {
                AccountDto.Info account = accountService.getByToken(token);
                if (account != null) {
                    String accountName = account.getAccount();
                    logAction.setAccount(accountName);
                }
            }

        }

        Object proceed = pjp.proceed();
        long endTimeMillis = System.currentTimeMillis();
        logAction.setTimeConsuming(new BigDecimal(endTimeMillis - startTimeMillis));
        if (actionWrite) {
            logActionService.save(logAction);
        }

        return proceed;
    }
}
