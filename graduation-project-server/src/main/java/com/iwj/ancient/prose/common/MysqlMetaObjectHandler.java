package com.iwj.ancient.prose.common;


import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;

import java.time.LocalDateTime;

/**
 * 自定义元对象字段填充控制器，实现公共字段自动写入
 *
 * @author avinzhang
 */
public class MysqlMetaObjectHandler implements MetaObjectHandler {

    /**
     * 执行insert的时候，给字段createTime createBy updateTime updateBy enableFlag corpKey设置默认值
     *
     * @param metaObject
     */
    @Override
    public void insertFill(MetaObject metaObject) {
        setFieldValByName("createTime", LocalDateTime.now(), metaObject);
        setFieldValByName("createBy", UserContext.getId() == 0L ? 6066 : UserContext.getId(), metaObject);
        setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        setFieldValByName("updateBy", UserContext.getId() == 0L ? 6066 : UserContext.getId(), metaObject);
        setFieldValByName("enableFlag", true, metaObject);
    }

    /**
     * 执行update的时候，给字段updateTime updateBy设置默认值
     *
     * @param metaObject
     */
    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName("updateTime", LocalDateTime.now(), metaObject);
        setFieldValByName("updateBy", UserContext.getId() == 0L ? 6066 : UserContext.getId(), metaObject);
    }
}