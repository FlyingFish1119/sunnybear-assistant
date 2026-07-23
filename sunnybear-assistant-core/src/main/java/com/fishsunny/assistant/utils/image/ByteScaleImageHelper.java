package com.fishsunny.assistant.utils.image;

import java.io.IOException;

public abstract class ByteScaleImageHelper implements ScaleImageHelper<byte[]> {

    protected byte[] image;

    protected ByteScaleImageHelper(byte[] image) {
        if (image.length == 0) {
            throw new IllegalArgumentException("image is empty");
        }
        this.image = image;
    }

    public abstract byte[] scaleImage(int maxPixel) throws IOException;
}
