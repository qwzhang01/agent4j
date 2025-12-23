package com.iwj.ancient.prose.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.iwj.ancient.prose.dto.DictDto;
import com.iwj.ancient.prose.entity.Dict;
import com.iwj.ancient.prose.mapper.DictMapper;
import com.iwj.ancient.prose.service.DictService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;


/**
 * <p>
 * 全局配置 服务实现类
 * </p>
 *
 * @author avinzhang
 * @since 2023-12-22
 */
@Service
@Slf4j
@AllArgsConstructor
@CacheConfig(cacheNames = {"dict"})
public class DictServiceImpl extends ServiceImpl<DictMapper, Dict> implements DictService {

    @Override
    public List<DictDto.Select> findByType(String type) {
        return findByTypes(Collections.singletonList(type));
    }

    @Override
    public List<DictDto.Select> findByTypes(List<String> types) {
        LambdaQueryWrapper<Dict> query = Wrappers.lambdaQuery(Dict.class);
        if (types != null && !types.isEmpty()) {
            query.in(Dict::getType, types);
        }
        List<Dict> list = list(query);
        if (list == null || list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(d -> {
            DictDto.Select select = new DictDto.Select();
            BeanUtils.copyProperties(d, select);
            return select;
        }).collect(Collectors.toList());
    }

    @Override
    public Map<String, String> findMapByType(String type) {
        List<DictDto.Select> list = findByType(type);
        if (list == null || list.isEmpty()) {
            return Collections.emptyMap();
        }
        return list.stream().collect(Collectors.toMap(k -> k.getKey(), v -> v.getValue()));
    }

    @Override
    public String findValue(String type, String key) {
        Map<String, String> map = findMapByType(type);
        return map.get(key);
    }

    @Override
    @Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRED)
    public Boolean put(String type, String key, String value) {
        LambdaUpdateWrapper<Dict> query = Wrappers.lambdaUpdate(Dict.class);
        query.eq(Dict::getType, type);
        query.eq(Dict::getKey, key);
        query.set(Dict::getValue, value);
        boolean update = update(query);
        return update;
    }

    @Override
    public boolean validKey(String type, String... key) {
        if (key == null || key.length == 0) {
            return Boolean.FALSE;
        }
        List<DictDto.Select> list = findByType(type);
        if (list == null) {
            return Boolean.FALSE;
        }
        List<String> keys = list.stream().map(DictDto.Select::getKey).collect(Collectors.toList());
        for (String k : key) {
            if (!keys.contains(k)) {
                return Boolean.FALSE;
            }
        }
        return Boolean.TRUE;
    }
}