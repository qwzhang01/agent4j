package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.entity.Account;

/**
 * <p>
 * 账号 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
public interface AccountService extends IService<Account> {
    Account getByAccount(String account);

    AccountDto.Info getByToken(String token);

    boolean validPwd(Account account, String password);

    String getToken(Account account);

    void logout(String token);

    void add(String account, String pwd);
}
