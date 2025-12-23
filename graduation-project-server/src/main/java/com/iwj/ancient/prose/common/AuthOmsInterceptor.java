package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.service.AccountService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static com.iwj.ancient.prose.dto.Permission.WX_VIP;

/**
 * 后台管理权限拦截器
 */
@Slf4j
@Component
public class AuthOmsInterceptor extends AbstractAuthInterceptor {
    @Autowired
    private AccountService accountService;

    @Override
    public AccountDto.Info getAccount(String token) {
        AccountDto.Info info = accountService.getByToken(token);
        if (info == null) {
            return null;
        }
        user(info);
        return info;
    }

    private void user(AccountDto.Info info) {
        List<String> roles = info.getRoles();
        if (roles == null || roles.isEmpty()) {
            info.setRoles(new ArrayList<String>() {{
                add(WX_VIP);
            }});
        } else {
            roles.add(WX_VIP);
        }
        List<String> rightItemCodes = info.getRightItemCodes();
        if (rightItemCodes == null || rightItemCodes.isEmpty()) {
            info.setRightItemCodes(new ArrayList<String>() {{
                add(WX_VIP);
            }});
        } else {
            rightItemCodes.add(WX_VIP);
        }
    }
}