package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 客户账号
 * </p>
 *
 * @author avinzhang
 * @since 2025-02-24
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("`oms_client`")
public class Client extends Model<Client> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 账号
     */
    @TableField("`account`")
    private String account;

    /**
     * 密码
     */
    @TableField("`password`")
    private String password;

    /**
     * 密码盐
     */
    @TableField("`salt`")
    private String salt;

    /**
     * 名称
     */
    @TableField("`name`")
    private String name;

    /**
     * 手机
     */
    @TableField("`phone`")
    private String phone;

    /**
     * 邮箱
     */
    @TableField("`email`")
    private String email;

    /**
     * 描述
     */
    @TableField("`desc`")
    private String desc;

    /**
     * 状态:enable,disable
     */
    @TableField("`status`")
    private String status;

    /**
     * 账号有效开始时间
     */
    @TableField("`startTime`")
    private LocalDateTime startTime;

    /**
     * 账号有效结尾时间
     */
    @TableField("`endTime`")
    private LocalDateTime endTime;

    /**
     * 创建时间
     */
    @TableField(value = "`createTime`", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更细时间
     */
    @TableField(value = "`updateTime`", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 创建人
     */
    @TableField(value = "`createBy`", fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 更新人
     */
    @TableField(value = "`updateBy`", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 逻辑删除
     */
    @TableField(value = "`enableFlag`", fill = FieldFill.INSERT)
    @TableLogic
    private Boolean enableFlag;


    @Override
    protected Serializable pkVal() {
        return this.id;
    }

}
