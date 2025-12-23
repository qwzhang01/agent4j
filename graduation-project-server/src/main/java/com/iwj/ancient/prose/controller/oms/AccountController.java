package com.iwj.ancient.prose.controller.oms;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.common.UserContext;
import com.iwj.ancient.prose.dto.AccountDto;
import com.iwj.ancient.prose.dto.PageQuery;
import com.iwj.ancient.prose.dto.enums.StatusEnum;
import com.iwj.ancient.prose.entity.Account;
import com.iwj.ancient.prose.exception.AncientException;
import com.iwj.ancient.prose.service.AccountService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.stream.Collectors;

@RestController
@RequestMapping("api/oms/account")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "OMS 账户")
public class AccountController {

    private final AccountService accountService;

    @PostMapping("list")
    public Page<AccountDto.Info> list(@RequestBody @Valid PageQuery form) {
        LambdaQueryWrapper<Account> query = Wrappers.lambdaQuery(Account.class);
        if (StringUtils.isNotBlank(form.getName())) {
            query.eq(Account::getAccount, form.getName());
        }
        query.orderByDesc(Account::getId);

        Page<Account> page = accountService.page(new Page<>(form.getCurrent(), form.getSize()), query);
        Page<AccountDto.Info> result = new Page<>(page.getCurrent(), page.getSize(), page.getCurrent());
        result.setRecords(page.getRecords().stream().map(s -> {
            AccountDto.Info info = new AccountDto.Info();
            BeanUtils.copyProperties(s, info);
            return info;
        }).collect(Collectors.toList()));
        return result;
    }

    /**
     * 获取登录信息接口
     */
    @GetMapping(value = "getInfo")
    public AccountDto.Info getInfo() {
        AccountDto.Info info = accountService.getByToken(UserContext.getToken());
        if (info == null) {
            throw new AncientException("Token 过期，无法登录！");
        }
        return info;
    }

    @ActionDesc(desc = "登录后台系统")
    @PostMapping(value = "login")
    public void login(@RequestBody @Valid AccountDto.Request request) {
        Account account = accountService.getByAccount(request.getAccount());
        if (account == null) {
            throw new AncientException("账户不存在");
        }
        if (!StatusEnum.ENABLE.getCode().equals(account.getStatus())) {
            throw new AncientException("账户禁用，无法登陆");
        }
        if (!accountService.validPwd(account, request.getPassword())) {
            throw new AncientException("账号或密码有误");
        }
        String token = accountService.getToken(account);
        UserContext.setToken(token);
    }

    /**
     * 退出后台登录
     */
    @GetMapping(value = "logout")
    @ActionDesc(desc = "退出后台系统")
    public void logout() {
        accountService.logout(UserContext.getToken());
    }
}
