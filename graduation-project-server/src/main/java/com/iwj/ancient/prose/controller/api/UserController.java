package com.iwj.ancient.prose.controller.api;

import com.iwj.ancient.prose.common.ActionDesc;
import com.iwj.ancient.prose.common.UserContext;
import com.iwj.ancient.prose.dto.ClientDto;
import com.iwj.ancient.prose.dto.enums.StatusEnum;
import com.iwj.ancient.prose.entity.Client;
import com.iwj.ancient.prose.exception.AncientException;
import com.iwj.ancient.prose.kit.CacheKit;
import com.iwj.ancient.prose.service.ClientService;
import io.swagger.annotations.ApiOperation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

import static com.iwj.ancient.prose.dto.CacheKey.ACCOUNT;
import static com.iwj.ancient.prose.kit.CacheKit.CACHE_TOKEN;

@RestController
@RequestMapping("api/user")
@Slf4j
@AllArgsConstructor
@ActionDesc(desc = "API 客户")
public class UserController {

    private final ClientService clientService;
    private final CacheKit cacheKit;

    @ActionDesc(desc = "注册")
    @PostMapping(value = "register")
    public void register(@RequestBody @Valid ClientDto.Register request) {
        Client account = clientService.register(request.getAccount(), request.getPassword());
        String token = clientService.getToken(account);
        UserContext.setToken(token);
    }

    @ActionDesc(desc = "登录")
    @PostMapping(value = "login")
    public void login(@RequestBody @Valid ClientDto.Login request) {
        Client account = clientService.getByAccount(request.getAccount());
        if (account == null) {
            throw new AncientException("账户不存在");
        }
        if (!StatusEnum.ENABLE.getCode().equals(account.getStatus())) {
            throw new AncientException("账户禁用，无法登陆");
        }
        if (!clientService.validPwd(account, request.getPassword())) {
            throw new AncientException("账号或密码有误");
        }
        String token = clientService.getToken(account);
        UserContext.setToken(token);
    }

    @ApiOperation("获取登录信息")
    @GetMapping(value = "getInfo")
    public ClientDto.Info getInfo() {
        return clientService.getByToken(UserContext.getToken());
    }

    @PostMapping("edit")
    public void edit(@RequestBody @Valid ClientDto.Edit form) {
        Long id = UserContext.getId();
        Client client = clientService.getById(id);
        client.setName(form.getName());
        client.setEmail(form.getEmail());
        client.setPhone(form.getPhone());
        clientService.updateById(client);

        cacheKit.removeOfNative(CACHE_TOKEN, ACCOUNT + UserContext.getToken());
    }

    @ApiOperation("退出登录")
    @GetMapping(value = "logout")
    @ActionDesc(desc = "退出登录")
    public void logout() {
        clientService.logout(UserContext.getToken());
    }
}
