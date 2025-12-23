package com.iwj.ancient.prose.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.activerecord.Model;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 站内信
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Data
@EqualsAndHashCode(callSuper = false)
@TableName("`oms_log_message`")
public class LogMessage extends Model<LogMessage> {

    private static final long serialVersionUID = 1L;

    /**
     * 主键
     */
    @TableId(value = "`id`", type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 标签:预警消息,知会消息,流程提醒
     */
    @TableField("`tagType`")
    private String tagType;

    /**
     * 业务类型
     */
    @TableField("`type`")
    private String type;
    @TableField("`title`")
    private String title;
    /**
     * 描述
     */
    @TableField("`profile`")
    private String profile;

    /**
     * 目标Id
     */
    @TableField("`targetId`")
    private Long targetId;
    @TableField("`userId`")
    private Long userId;

    /**
     * 阅读状态
     */
    @TableField("`readFlag`")
    private Boolean readFlag;

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
