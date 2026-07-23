package com.fishsunny.assistant.utils.image;

import org.springframework.util.StringUtils;

import java.io.IOException;

public abstract class StringScaleImageHelper implements ScaleImageHelper<String> {

    protected String imageBase64;

    protected String imageType;

    protected StringScaleImageHelper(String imageBase64) {
        if (!StringUtils.hasText(imageBase64)) {
            throw new IllegalArgumentException("imageBase64 is empty");
        }
        this.imageBase64 = imageBase64;
    }

    public abstract String scaleImage(int maxLength) throws IOException;
}
