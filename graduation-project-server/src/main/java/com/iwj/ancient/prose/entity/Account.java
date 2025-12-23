package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 账号
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_account`")
public class Account extends Model<Account> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 所属店铺id  : -1 代表全部
     */
    @TableField("`storeId`")
    private Long storeId;

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
     * 状态:enable,disable
     */
    @TableField("`status`")
    private String status;

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
