package com.iwj.ancient.prose.common;

import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.dto.ClientDto;
import com.iwj.ancient.prose.service.ClientService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

/**
 * C端客户权限拦截器
 *
 * @author avinzhang
 */
@Slf4j
@Component
public class AuthApiInterceptor extends AbstractAuthInterceptor {
    @Autowired
    private ClientService accountService;

    @Override
    protected String defaultToken() {
        return "guest";
    }

    @Override
    public AccountDto.Info getAccount(String token) {
        ClientDto.Info client = accountService.getByToken(token);
        if (client == null) {
            return null;
        }
        return guest(client);
    }


    /**
     * 有课权限
     *
     * @return
     */
    private AccountDto.Info guest(ClientDto.Info client) {
        AccountDto.Info info = new AccountDto.Info();
        info.setId(client.getId());
        info.setName(client.getName());
        info.setAccount(client.getAccount());
        info.setRoles(new ArrayList<String>() {{
            add("");
        }});
        info.setRightItemCodes(new ArrayList<String>() {{
            add("");
        }});
        return info;
    }
}