INSERT INTO `oms_account` (`id`, `storeId`, `account`, `password`, `salt`, `name`, `status`, `createTime`, `updateTime`, `createBy`, `updateBy`, `enableFlag`)
VALUES
	('1', '0', 'admin', '890e8167277d95088f4f555ac0f5ac56691f33bf', '7556239de3714c59', '请问', 'enable', '2023-02-11 00:00:00', '2025-02-21 19:55:27', '0', '6066', '1');

INSERT INTO `oms_role` (`id`, `name`, `status`, `desc`, `createTime`, `updateTime`, `createBy`, `updateBy`, `enableFlag`)
VALUES
	('1', '超级管理员', 'enable', '', '2023-01-01 00:00:00', '2023-01-01 00:00:00', '10', '10', '1');

INSERT INTO `oms_right_item` (`id`, `name`, `parentId`, `menuType`, `itemCode`)
VALUES
	('1', '账户管理', '0', '', 'account');


INSERT INTO `oms_role_right` (`roleId`, `rightId`)
VALUES
	('1', '1');


INSERT INTO `oms_account_role` (`accountId`, `roleId`)
VALUES
	('1', '1');
