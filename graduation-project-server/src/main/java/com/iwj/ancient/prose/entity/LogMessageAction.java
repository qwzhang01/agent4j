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
 * 站内信
 * </p>
 *
 * @author avinzhang
 * @since 2025-02-25
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("`oms_log_message_action`")
public class LogMessageAction extends Model<LogMessageAction> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("`userId`")
    private Long userId;

    /**
     * 消息id
     */
    @TableField("`messageId`")
    private Long messageId;

    /**
     * 操作类型:阅读、点赞、倒赞
     */
    @TableField("`type`")
    private String type;

    /**
     * 创建时间
     */
    @TableField(value = "`createTime`", fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 创建人
     */
    @TableField(value = "`createBy`", fill = FieldFill.INSERT)
    private Long createBy;

    /**
     * 更新时间
     */
    @TableField(value = "`updateTime`", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 更新人
     */
    @TableField(value = "`updateBy`", fill = FieldFill.INSERT_UPDATE)
    private Long updateBy;

    /**
     * 删除标识符,正常1,删除0
     */
    @TableField(value = "`enableFlag`", fill = FieldFill.INSERT)
    @TableLogic
    private Boolean enableFlag;


    @Override
    protected Serializable pkVal() {
        return this.id;
    }

}
