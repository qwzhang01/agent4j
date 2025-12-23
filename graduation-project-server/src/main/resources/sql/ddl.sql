DROP TABLE IF EXISTS `cms_action`;
CREATE TABLE `cms_action`
(
  `id` bigint NOT NULL COMMENT '主键',
  `contentId` bigint NOT NULL COMMENT '内容主键',
  `action` varchar(20) NOT NULL DEFAULT '' COMMENT '动作:点赞,已读',
  `userId` bigint NOT NULL DEFAULT '0' COMMENT '用户ID',
  PRIMARY KEY (`id`)
)
    ENGINE=InnoDB
    DEFAULT CHARSET = utf8mb3
    ROW_FORMAT = COMPACT
    COMMENT = '阅读&点赞';


DROP TABLE IF EXISTS `oms_dict`;
CREATE TABLE IF NOT EXISTS `oms_dict`
(
    `id`         BIGINT(20)    NOT NULL COMMENT '主键',
    `status`     VARCHAR(20)   NOT NULL DEFAULT '' COMMENT '状态:enable,disable',
    `type`       VARCHAR(20)   NOT NULL DEFAULT '' COMMENT '类型',
    `key`        VARCHAR(50)   NOT NULL DEFAULT '' COMMENT '配置项',
    `value`      VARCHAR(1000) NOT NULL DEFAULT '' COMMENT '配置值',
    `remark`     VARCHAR(200)  NOT NULL DEFAULT '' COMMENT '配置说明',
    `createTime` DATETIME      NOT NULL COMMENT '创建时间',
    `updateTime` DATETIME      NOT NULL COMMENT '更细时间',
    `createBy`   BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '创建人',
    `updateBy`   BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '更新人',
    `enableFlag` TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '全局配置';


DROP TABLE IF EXISTS `oms_log_action`;
CREATE TABLE IF NOT EXISTS `oms_log_action`
(
    `id`            BIGINT(20)     NOT NULL COMMENT '主键',
    `actionTime`    DATETIME       NOT NULL COMMENT '操作时间',
    `timeConsuming` DECIMAL(11, 0) NOT NULL DEFAULT 0 COMMENT '耗时',
    `clientIp`      VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '客户端IP',
    `module`        VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '操作模块',
    `url`           VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '请求URL',
    `account`       VARCHAR(50)    NOT NULL DEFAULT '' COMMENT '操作用户账户',
    `userAgent`     VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '用户系统以及浏览器信息',
    `actionDesc`    VARCHAR(500)   NOT NULL DEFAULT '' COMMENT '操作内容',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '操作记录'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_log_message`;
CREATE TABLE IF NOT EXISTS `oms_log_message`
(
    `id`         BIGINT(20)    NOT NULL COMMENT '主键',
    `tagType`    VARCHAR(20)   NOT NULL DEFAULT '' COMMENT '标签:预警消息,知会消息,流程提醒',
    `type`       VARCHAR(20)   NOT NULL DEFAULT '' COMMENT '业务类型',
    `targetId`   BIGINT(20)    NOT NULL COMMENT '业务ID',
    `title`      VARCHAR(200) NOT NULL DEFAULT '' COMMENT '站内信标题',
    `profile`    VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '站内信内容',
    `userId`     BIGINT(20)    NOT NULL COMMENT '用户ID',
    `readFlag`   TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '阅读状态',
    `createTime` DATETIME      NOT NULL COMMENT '创建时间',
    `createBy`   BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '创建人',
    `updateTime` DATETIME      NOT NULL COMMENT '更新时间',
    `updateBy`   BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '更新人',
    `enableFlag` TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '删除标识符,正常1,删除0',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    AUTO_INCREMENT = 2
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '站内信'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_log_message_action`;
CREATE TABLE IF NOT EXISTS `oms_log_message_action`
(
    `id`         BIGINT(20)    NOT NULL COMMENT '主键',
    `userId`     BIGINT(20)    NOT NULL COMMENT '用户ID',
    `messageId`  BIGINT(20)    NOT NULL COMMENT '消息id',
    `type`       VARCHAR(20)    NOT NULL COMMENT '操作类型:阅读、点赞、倒赞',
    `createTime` DATETIME      NOT NULL COMMENT '创建时间',
    `createBy`   BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '创建人',
    `updateTime` DATETIME      NOT NULL COMMENT '更新时间',
    `updateBy`   BIGINT(20)    NOT NULL DEFAULT 0 COMMENT '更新人',
    `enableFlag` TINYINT(1)    NOT NULL DEFAULT 1 COMMENT '删除标识符,正常1,删除0',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    AUTO_INCREMENT = 2
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '站内信'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_account`;
CREATE TABLE IF NOT EXISTS `oms_account`
(
    `id`         BIGINT(20)   NOT NULL COMMENT '主键',
    `storeId`    BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '所属店铺id  : -1 代表全部',
    `account`    VARCHAR(100) NOT NULL DEFAULT '' COMMENT '账号',
    `password`   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '密码',
    `salt`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '密码盐',
    `name`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '名称',
    `status`     VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '状态:enable,disable',
    `createTime` DATETIME     NOT NULL COMMENT '创建时间',
    `updateTime` DATETIME     NOT NULL COMMENT '更细时间',
    `createBy`   BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '创建人',
    `updateBy`   BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '更新人',
    `enableFlag` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    AUTO_INCREMENT = 2
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '后台账号'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_account_role`;
CREATE TABLE IF NOT EXISTS `oms_account_role`
(
    `accountId` BIGINT(20) NOT NULL COMMENT '账户ID',
    `roleId`    BIGINT(20) NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`accountId`, `roleId`)
)
    ENGINE = InnoDB
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '用户角色'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_role`;
CREATE TABLE IF NOT EXISTS `oms_role`
(
    `id`         BIGINT(20)   NOT NULL COMMENT '主键',
    `name`       VARCHAR(400) NOT NULL DEFAULT '' COMMENT '名称',
    `status`     VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '状态:enable,disable',
    `desc`       VARCHAR(500) NOT NULL DEFAULT '' COMMENT '描述',
    `createTime` DATETIME     NOT NULL COMMENT '创建时间',
    `updateTime` DATETIME     NOT NULL COMMENT '更细时间',
    `createBy`   BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '创建人',
    `updateBy`   BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '更新人',
    `enableFlag` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    AUTO_INCREMENT = 2
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '角色'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_role_right`;
CREATE TABLE IF NOT EXISTS `oms_role_right`
(
    `roleId`  BIGINT(20) NOT NULL COMMENT '角色ID',
    `rightId` BIGINT(20) NOT NULL COMMENT '权限项ID',
    PRIMARY KEY (`roleId`, `rightId`)
)
    ENGINE = InnoDB
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '角色权限项'
    ROW_FORMAT = COMPACT;


DROP TABLE IF EXISTS `oms_right_item`;
CREATE TABLE `oms_right_item`
(
  `id` bigint NOT NULL COMMENT '主键',
  `name` varchar(100) NOT NULL DEFAULT '' COMMENT '名称',
  `parentId` bigint NOT NULL DEFAULT '0' COMMENT '上级ID',
  `menuType` varchar(10) NOT NULL DEFAULT '' COMMENT '一级菜单&二级菜单&按钮',
  `itemCode` varchar(100) NOT NULL COMMENT '权限标识',
  PRIMARY KEY (`id`),
  UNIQUE KEY `itemCode` (`itemCode`)
)
    ENGINE=InnoDB
    DEFAULT CHARSET=utf8mb3
    ROW_FORMAT=COMPACT
    COMMENT='权限项';


DROP TABLE IF EXISTS `oms_client`;
CREATE TABLE IF NOT EXISTS `oms_client`
(
    `id`         BIGINT(20)   NOT NULL COMMENT '主键',
    `account`    VARCHAR(100) NOT NULL DEFAULT '' COMMENT '账号',
    `password`   VARCHAR(100) NOT NULL DEFAULT '' COMMENT '密码',
    `salt`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '密码盐',
    `name`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '名称',
    `phone`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '手机',
    `email`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '邮箱',
    `desc`       VARCHAR(100) NOT NULL DEFAULT '' COMMENT '描述',
    `status`     VARCHAR(20)  NOT NULL DEFAULT '' COMMENT '状态:enable,disable',
    `startTime`  DATETIME     NOT NULL COMMENT '账号有效开始时间',
    `endTime`    DATETIME     NOT NULL COMMENT '账号有效结尾时间',
    `createTime` DATETIME     NOT NULL COMMENT '创建时间',
    `updateTime` DATETIME     NOT NULL COMMENT '更细时间',
    `createBy`   BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '创建人',
    `updateBy`   BIGINT(20)   NOT NULL DEFAULT 0 COMMENT '更新人',
    `enableFlag` TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '逻辑删除',
    PRIMARY KEY (`id`)
)
    ENGINE = InnoDB
    AUTO_INCREMENT = 2
    DEFAULT CHARACTER SET = utf8mb3
    COMMENT = '客户账号'
    ROW_FORMAT = COMPACT;