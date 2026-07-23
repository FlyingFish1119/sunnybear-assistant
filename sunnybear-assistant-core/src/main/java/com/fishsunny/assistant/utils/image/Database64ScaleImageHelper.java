package com.fishsunny.assistant.utils.image;

import net.coobird.thumbnailator.Thumbnails;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;

public class Database64ScaleImageHelper extends StringScaleImageHelper {

    public Database64ScaleImageHelper(String imageBase64) {
        super(imageBase64);
        if (imageBase64.startsWith("data:image/")) {
            int startIndex = imageBase64.indexOf("data:image/") + "data:image/".length();
            int endIndex = imageBase64.indexOf(";base64,");
            super.imageType = imageBase64.substring(startIndex, endIndex);
            imageBase64 = imageBase64.substring(endIndex + ";base64,".length());
        }
        super.imageBase64 = imageBase64;
    }

    @Override
    public String scaleImage(int maxLength) throws IOException {
        byte[] image = Base64.getDecoder().decode(super.imageBase64);
        BufferedImage originBufferImage = ImageIO.read(new ByteArrayInputStream(image));
        int maxImageLength = Math.max(originBufferImage.getWidth(), originBufferImage.getHeight());
        double scaleRate = maxLength / (double) maxImageLength;
        scaleRate = scaleRate > 1 ? 1 : scaleRate;
        BufferedImage bufferedImage;
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        String extendBase64;
        if (super.imageType == null) {
            bufferedImage = Thumbnails.of(originBufferImage).scale(scaleRate).outputFormat("jpg").asBufferedImage();
            ImageIO.write(bufferedImage, "png", outputStream);
            extendBase64 = "data:image/png;base64,";
        } else {
            bufferedImage = Thumbnails.of(originBufferImage).scale(scaleRate).outputFormat(imageType).asBufferedImage();
            ImageIO.write(bufferedImage, super.imageType, outputStream);
            extendBase64 = "data:image/" + super.imageType + ";base64,";
        }
        return extendBase64 + Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }
}
