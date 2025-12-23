package com.iwj.ancient.prose.kit;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;

/**
 * Base64 工具
 *
 * @author avinzhang
 */
public class Base64Kit {
    public static String decode(String base64Str) {
        byte[] decode = Base64.getDecoder().decode(base64Str);
        return new String(decode);
    }

    public static String encode(InputStream is) throws IOException {
        byte[] data = null;
        try {
            ByteArrayOutputStream swapStream = new ByteArrayOutputStream();
            byte[] buff = new byte[100];
            int rc = 0;
            while ((rc = is.read(buff, 0, 100)) > 0) {
                swapStream.write(buff, 0, rc);
            }
            data = swapStream.toByteArray();
        } finally {
            if (is != null) {
                is.close();
            }
        }

        return Base64.getEncoder().encodeToString(data);
    }
}