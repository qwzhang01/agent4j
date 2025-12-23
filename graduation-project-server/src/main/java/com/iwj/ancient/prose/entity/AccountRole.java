package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

/**
 * <p>
 * 用户角色
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_account_role`")
public class AccountRole extends Model<AccountRole> {

    private static final long serialVersionUID = 1L;

    /**
     * 账户ID
     */
    @TableId(value = "`accountId`", type = IdType.ASSIGN_ID)
    private Long accountId;

    /**
     * 角色ID
     */
    @TableField("`roleId`")
    private Long roleId;


    @Override
    protected Serializable pkVal() {
        return this.accountId;
    }

}
