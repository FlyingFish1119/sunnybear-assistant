package com.fishsunny.assistant.utils.image;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public class MultipartScaleImageHelper extends ByteScaleImageHelper {

    public String extension;

    public MultipartScaleImageHelper(byte[] image) {
        super(image);
        this.extension = "png";
    }

    public MultipartScaleImageHelper(byte[] image, String extension) {
        super(image);
        this.extension = extension;
    }

    public MultipartScaleImageHelper(MultipartFile file) throws IOException {
        super(file.getBytes());
        if(file.getOriginalFilename() != null) {
            int dotIndex = file.getOriginalFilename().lastIndexOf(".");
            extension = dotIndex >= 0 ? file.getOriginalFilename().substring(dotIndex + 1) : "png";
        } else {
            extension = "png";
        }
    }

    @Override
    public byte[] scaleImage(int maxLength) throws IOException {
        BufferedImage originBufferImage = ImageIO.read(new ByteArrayInputStream(image));
        int maxImageLength = Math.max(originBufferImage.getWidth(), originBufferImage.getHeight());
        double scaleRate = maxLength / (double) maxImageLength;
        scaleRate = scaleRate > 1 ? 1 : scaleRate;
        BufferedImage bufferedImage = Thumbnails.of(originBufferImage).scale(scaleRate).asBufferedImage();
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, extension, outputStream);
        return outputStream.toByteArray();
    }
}
