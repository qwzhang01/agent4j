package com.iwj.ancient.prose.kit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.iwj.ancient.prose.exception.JacksonException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * json 工具
 *
 * @author avinzhang
 */
@Component
public class JsonKit {
    private static JsonKit KIT;

    @Autowired
    private ObjectMapper objectMapper;

    public static ObjectMapper getObjectMapper() {
        return KIT.objectMapper;
    }

    /**
     * 对象转Json格式字符串(常用)
     *
     * @param obj 对象
     * @return Json格式字符串
     */
    public static <T> String obj2String(T obj) {
        if (obj == null) {
            return null;
        }
        try {
            return obj instanceof String ? (String) obj : KIT.objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JacksonException("Json序列化异常:" + e.getLocalizedMessage(), "", obj.getClass());
        }
    }

    /**
     * 对象转Json格式字符串(格式化的Json字符串)
     *
     * @param obj 对象
     * @return 美化的Json格式字符串
     */
    public static <T> String obj2StringPretty(T obj) {
        if (obj == null) {
            return null;
        }
        try {
            return obj instanceof String ? (String) obj : KIT.objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new JacksonException("Json序列化异常:" + e.getLocalizedMessage(), "", obj.getClass());
        }
    }

    /**
     * 字符串转换为自定义对象（常用）
     *
     * @param str   要转换的字符串
     * @param clazz 自定义对象的class对象
     * @return 自定义对象
     */
    public static <T> T string2Obj(String str, Class<T> clazz) {
        if (StringUtils.isEmpty(str) || clazz == null) {
            return null;
        }
        try {
            return clazz.equals(String.class) ? (T) str : KIT.objectMapper.readValue(str, clazz);
        } catch (Exception e) {
            throw new JacksonException("Json反序列化异常:" + e.getLocalizedMessage(), str, clazz);
        }
    }

    /**
     * 字符串转换为集合对象（常用）
     *
     * @param str           要转换的字符串
     * @param typeReference 集合TypeReference
     * @param <T>           集合对象
     * @return 集合对象
     */
    public static <T> T string2Obj(String str, TypeReference<T> typeReference) {
        if (StringUtils.isEmpty(str) || typeReference == null) {
            return null;
        }
        try {
            return (T) (typeReference.getType().equals(String.class) ? str : KIT.objectMapper.readValue(str, typeReference));
        } catch (IOException e) {
            throw new JacksonException("Json反序列化异常:" + e.getLocalizedMessage(), str, typeReference.getClass());
        }
    }

    public static void toFile(File file, List<?> list) {
        try {
            KIT.objectMapper.writerWithDefaultPrettyPrinter().writeValue(file, list);
        } catch (IOException e) {
            throw new JacksonException("Json序列化异常:" + e.getLocalizedMessage(), "", list.getClass());
        }
    }

    @PostConstruct
    public void init() {
        KIT = this;
    }
}