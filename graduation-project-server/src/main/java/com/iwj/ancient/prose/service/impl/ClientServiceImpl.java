package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.dto.ClientDto;
import com.iwj.ancient.prose.entity.Client;
import com.iwj.ancient.prose.exception.ParamException;
import com.iwj.ancient.prose.kit.CacheKit;
import com.iwj.ancient.prose.kit.Digests;
import com.iwj.ancient.prose.kit.Encodes;
import com.iwj.ancient.prose.mapper.ClientMapper;
import com.iwj.ancient.prose.service.ClientService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.iwj.ancient.prose.dto.CacheKey.ACCOUNT;
import static com.iwj.ancient.prose.dto.CacheKey.TOKEN;
import static com.iwj.ancient.prose.dto.enums.StatusEnum.ENABLE;
import static com.iwj.ancient.prose.kit.CacheKit.CACHE_TOKEN;

/**
 * <p>
 * 客户账号 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2025-02-24
 */
@Service
@Slf4j
@AllArgsConstructor
@CacheConfig(cacheNames = {"account"})
public class ClientServiceImpl extends ServiceImpl<ClientMapper, Client> implements ClientService {
    private final CacheKit cacheKit;

    @Override
    public Client getByAccount(String account) {
        LambdaQueryWrapper<Client> query = Wrappers.lambdaQuery(Client.class);
        query.eq(Client::getAccount, account);
        return getOne(query);
    }

    @Override
    public boolean validPwd(Client account, String password) {
        byte[] salt1 = Encodes.decodeHex(account.getSalt());
        byte[] hashPassword = Digests.sha1(password.getBytes(), salt1, 1024);
        String encodeHex = Encodes.encodeHex(hashPassword);
        return encodeHex.equals(account.getPassword());
    }

    /**
     * 生成token
     *
     * @param pwd
     */
    private String entryPassword(String pwd) {
        byte[] salt = Digests.generateSalt(8);
        byte[] hashPassword = Digests.sha1(pwd.getBytes(), salt, 1024);
        pwd = Encodes.encodeHex(hashPassword);
        return pwd;
    }

    /**
     * 生成密码
     *
     * @param account
     */
    private void entryPassword(Client account) {
        byte[] salt = Digests.generateSalt(8);

        String pwd = account.getPassword();
        byte[] hashPassword = Digests.sha1(pwd.getBytes(), salt, 1024);
        pwd = Encodes.encodeHex(hashPassword);

        account.setSalt(Encodes.encodeHex(salt));
        account.setPassword(pwd);
    }

    @Override
    public String getToken(Client account) {
        byte[] bytes = Digests.generateSalt(16);
        String token = entryPassword(Encodes.encodeHex(bytes));
        cacheKit.putOfNative(CACHE_TOKEN, TOKEN + token, account.getId());
        return token;
    }

    @Override
    public ClientDto.Info getByToken(String token) {
        ClientDto.Info info = cacheKit.getByNative(CACHE_TOKEN, ACCOUNT + token, ClientDto.Info.class);
        if (info != null) {
            return info;
        }

        Long accountId = cacheKit.getByNative(CACHE_TOKEN, TOKEN + token, Long.class);
        if (accountId == null) {
            return null;
        }

        Client account = getById(accountId);
        info = new ClientDto.Info();
        info.setId(account.getId());
        info.setAccount(account.getAccount());
        info.setEmail(account.getEmail());
        info.setPhone(account.getPhone());
        info.setStartTime(account.getStartTime());
        info.setEndTime(account.getEndTime());
        info.setName(account.getName());

        cacheKit.putOfNative(CACHE_TOKEN, ACCOUNT + token, info);
        return info;
    }

    @Override
    public void logout(String token) {
        cacheKit.removeOfNative(CACHE_TOKEN, TOKEN + token);
        cacheKit.removeOfNative(CACHE_TOKEN, ACCOUNT + token);
    }

    @Override
    public Client register(String account, String password) {
        if (getByAccount(account) != null) {
            throw new ParamException("账号已经注册，无法重复注册！");
        }

        Client client = new Client();
        client.setAccount(account);
        client.setName("");
        client.setDesc("");
        client.setPhone("");
        client.setEmail("");
        client.setStartTime(LocalDateTime.now());
        client.setEndTime(LocalDateTime.now().plusYears(4));
        client.setStatus(ENABLE.getCode());
        client.setPassword(password);
        entryPassword(client);
        save(client);

        return client;
    }
}
