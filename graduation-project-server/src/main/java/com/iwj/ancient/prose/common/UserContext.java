package com.iwj.ancient.prose.common;


import com.iwj.ancient.prose.dto.AccountDto;

public class UserContext {
    private static final ThreadLocal<AccountDto.Context> USER_CONTEXT = new InheritableThreadLocal<>();

    public static void setCurrentUser(AccountDto.Context context) {
        USER_CONTEXT.set(context);
    }

    public static AccountDto.Context getContext() {
        return USER_CONTEXT.get();
    }

    public static String getToken() {
        AccountDto.Context context = USER_CONTEXT.get();
        if (context == null) {
            return "";
        }
        return context.getToken();
    }

    public static void setToken(String token) {
        AccountDto.Context context = getContext();
        if (context == null) {
            context = new AccountDto.Context();
            USER_CONTEXT.set(context);
        }
        context.setToken(token);
    }

    public static String getIp() {
        AccountDto.Context context = USER_CONTEXT.get();
        if (context == null) {
            return "";
        }
        return context.getIp();
    }

    public static Long getId() {
        AccountDto.Context context = USER_CONTEXT.get();
        if (context == null) {
            return 0L;
        }
        return context.getId();
    }

    public static void remove() {
        USER_CONTEXT.remove();
    }
}