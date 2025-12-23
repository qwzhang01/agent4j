package com.iwj.ancient.prose.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.iwj.ancient.prose.dto.ClientDto;
import com.iwj.ancient.prose.entity.Client;

/**
 * <p>
 * 客户账号 服务类
 * </p>
 *
 * @author avinzhang
 * @since 2025-02-24
 */
public interface ClientService extends IService<Client> {

    Client getByAccount(String account);

    boolean validPwd(Client account, String password);

    String getToken(Client account);

    ClientDto.Info getByToken(String token);

    void logout(String token);

    Client register(String account, String password);
}
