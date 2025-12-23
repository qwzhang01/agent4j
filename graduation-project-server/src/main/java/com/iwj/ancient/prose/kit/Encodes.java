package com.iwj.ancient.prose.kit;

import com.iwj.ancient.prose.exception.AncientException;
import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;

/**
 * 编码转换工具类
 *
 * @author avinzhang
 */
public class Encodes {

    /**
     * Hex编码.
     */
    public static String encodeHex(byte[] input) {
        return Hex.encodeHexString(input);
    }

    /**
     * Hex解码.
     */
    public static byte[] decodeHex(String input) {
        try {
            return Hex.decodeHex(input.toCharArray());
        } catch (DecoderException e) {
            throw new AncientException(e);
        }
    }
}
