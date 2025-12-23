package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.dto.enums.StatusEnum;
import com.iwj.ancient.prose.entity.Account;
import com.iwj.ancient.prose.entity.Role;
import com.iwj.ancient.prose.exception.AncientException;
import com.iwj.ancient.prose.kit.CacheKit;
import com.iwj.ancient.prose.kit.Digests;
import com.iwj.ancient.prose.kit.Encodes;
import com.iwj.ancient.prose.mapper.AccountMapper;
import com.iwj.ancient.prose.service.AccountService;
import com.iwj.ancient.prose.service.RightItemService;
import com.iwj.ancient.prose.service.RoleService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static com.iwj.ancient.prose.dto.CacheKey.OMS_ACCOUNT;
import static com.iwj.ancient.prose.dto.CacheKey.OMS_TOKEN;
import static com.iwj.ancient.prose.kit.CacheKit.CACHE_TOKEN;


/**
 * <p>
 * 账号 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
@Slf4j
@AllArgsConstructor
@CacheConfig(cacheNames = {"account"})
public class AccountServiceImpl extends ServiceImpl<AccountMapper, Account> implements AccountService {
    private final CacheKit cacheKit;
    private final RoleService roleService;
    private final RightItemService rightItemService;

    @Override
    public Account getByAccount(String account) {
        LambdaQueryWrapper<Account> query = Wrappers.lambdaQuery(Account.class);
        query.eq(Account::getAccount, account);
        return getOne(query);
    }

    @Override
    public boolean validPwd(Account account, String password) {
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
    private void entryPassword(Account account) {
        byte[] salt = Digests.generateSalt(8);

        String pwd = account.getPassword();
        byte[] hashPassword = Digests.sha1(pwd.getBytes(), salt, 1024);
        pwd = Encodes.encodeHex(hashPassword);

        account.setSalt(Encodes.encodeHex(salt));
        account.setPassword(pwd);
    }

    @Override
    public String getToken(Account account) {
        byte[] bytes = Digests.generateSalt(16);
        String token = entryPassword(Encodes.encodeHex(bytes));
        cacheKit.putOfNative(CACHE_TOKEN, OMS_TOKEN + token, account.getId());
        return token;
    }

    @Override
    public void logout(String token) {
        cacheKit.removeOfNative(CACHE_TOKEN, OMS_TOKEN + token);
        cacheKit.removeOfNative(CACHE_TOKEN, OMS_ACCOUNT + token);
    }

    @Override
    public AccountDto.Info getByToken(String token) {
        AccountDto.Info info = cacheKit.getByNative(CACHE_TOKEN, OMS_ACCOUNT + token, AccountDto.Info.class);
        if (info != null) {
            return info;
        }

        Long accountId = cacheKit.getByNative(CACHE_TOKEN, OMS_TOKEN + token, Long.class);
        if (accountId == null) {
            return null;
        }
        Account account = getById(accountId);
        List<Role> roles = roleService.findByAccountId(accountId);
        List<String> items = rightItemService.findByAccountId(accountId);

        info = new AccountDto.Info();
        info.setId(account.getId());
        info.setAccount(account.getAccount());
        info.setName(account.getName());
        if (roles != null) {
            info.setRoles(roles.stream().map(Role::getName).collect(Collectors.toList()));
        }
        info.setRightItemCodes(items);

        cacheKit.putOfNative(CACHE_TOKEN, OMS_ACCOUNT + token, info);

        return info;
    }

    private void uniqueAccountValid(Long id, String account) {
        LambdaQueryWrapper<Account> query = Wrappers.lambdaQuery(Account.class);
        query.eq(Account::getAccount, account);
        if (id != null && id > 0) {
            query.ne(Account::getId, id);
        }
        if (count(query) > 0) {
            throw new AncientException("手机&账号重复，无法使用");
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void add(String account, String pwd) {
        uniqueAccountValid(null, account);
        Account bean = new Account();
        bean.setAccount(account);
        bean.setStatus(StatusEnum.ENABLE.getCode());
        bean.setName(account);
        bean.setPassword(pwd);
        entryPassword(bean);
        saveOrUpdate(bean);
    }
}

