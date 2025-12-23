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
 * 阅读&点赞
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`cms_action`")
public class Action extends Model<Action> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 内容主键
     */
    @TableField("`contentId`")
    private Long contentId;

    /**
     * 动作:点赞,已读
     */
    @TableField("`action`")
    private String action;

    /**
     * 用户ID
     */
    @TableField("`userId`")
    private Long userId;


    @Override
    protected Serializable pkVal() {
        return this.id;
    }

}
