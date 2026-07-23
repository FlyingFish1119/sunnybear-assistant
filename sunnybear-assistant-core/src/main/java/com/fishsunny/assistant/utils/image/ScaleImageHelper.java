package com.fishsunny.assistant.utils.image;

import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Base64;

public interface ScaleImageHelper<T> {

    public T scaleImage(int maxPixel) throws IOException;

    public static boolean checkIsImage(String fileName) {
        String imageFormat = fileName.substring(fileName.lastIndexOf("."));
        return switch (imageFormat) {
            case ".jpg", ".png", ".jpeg", ".gif", ".bmp", ".webp" -> true;
            default -> false;
        };
    }

    public static byte[] base64ToByteArray(String image) {
        if (! StringUtils.hasText(image)) {
            return new byte[0];
        }
        if (image.startsWith("data:image/")) {
            int endIndex = image.indexOf(";base64,");
            image = image.substring(endIndex + ";base64,".length());
        }
        return Base64.getDecoder().decode(image);
    }

    public static String byteArrayToBase64(byte[] image) {
        if (image == null || image.length == 0) {
            return "";
        }
        String base64Image = Base64.getEncoder().encodeToString(image);
        return "data:image/png;base64," + base64Image;
    }

    public static String byteArrayToBase64(byte[] image, String extension) {
        if (image == null || image.length == 0) {
            return "";
        }
        String base64Image = Base64.getEncoder().encodeToString(image);
        return "data:image/" + extension + ";base64," + base64Image;
    }
}
