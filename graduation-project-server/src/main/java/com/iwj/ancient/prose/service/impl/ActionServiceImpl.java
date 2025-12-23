package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.dto.enums.ContentActionEnum;
import com.iwj.ancient.prose.entity.Action;
import com.iwj.ancient.prose.mapper.ActionMapper;
import com.iwj.ancient.prose.service.ActionService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * <p>
 * 阅读&点赞 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
public class ActionServiceImpl extends ServiceImpl<ActionMapper, Action> implements ActionService {

    @Override
    public Long findLatest(Long userId) {
        LambdaQueryWrapper<Action> query = Wrappers.lambdaQuery(Action.class);
        query.eq(Action::getAction, ContentActionEnum.UNREAD.getCode());
        query.eq(Action::getUserId, userId);

        Page<Action> page = page(new Page<>(1, 1), query);
        if (page.getRecords() == null || page.getRecords().isEmpty()) {
            return null;
        }
        return page.getRecords().get(0).getContentId();
    }

    @Override
    public void read(Long contentId, Long userId) {
        LambdaQueryWrapper<Action> query = Wrappers.lambdaQuery(Action.class);
        query.eq(Action::getContentId, contentId);
        query.eq(Action::getUserId, userId);
        query.eq(Action::getAction, ContentActionEnum.UNREAD.getCode());
        remove(query);
    }

    @Override
    public void unRead(Long contentId, List<Long> userIds) {
        if (userIds != null && !userIds.isEmpty()) {
            List<Action> list = userIds.stream().map(u -> {
                Action action = new Action();
                action.setAction(ContentActionEnum.UNREAD.getCode());
                action.setContentId(contentId);
                action.setUserId(u);
                return action;
            }).collect(Collectors.toList());

            saveBatch(list);
        }
    }

    @Override
    public boolean validDelete(List<Long> contentIds) {
        LambdaQueryWrapper<Action> query = Wrappers.lambdaQuery(Action.class);
        query.eq(Action::getAction, ContentActionEnum.UNREAD.getCode());
        query.in(Action::getContentId, contentIds);
        return count(query) > 0;
    }
}
