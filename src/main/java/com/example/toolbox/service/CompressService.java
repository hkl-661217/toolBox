package com.example.toolbox.service;

import net.coobird.thumbnailator.Thumbnails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

@Service
public class CompressService {
    private static final Logger log = LoggerFactory.getLogger(CompressService.class);
    private static final int QUALITY_SEARCH_ROUNDS = 12;

    public byte[] compressToTargetKB(MultipartFile file, Integer targetKB) throws IOException {
        long targetBytes = targetKB * 1024L;
        byte[] originalBytes = file.getBytes();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalBytes));
        if (image == null) {
            throw new IOException("无法解析图片内容");
        }

        byte[] result = compress(image, 1.0f);

        // 原图最高质量已经不超过目标时，直接补齐即可。
        // 继续放大分辨率只会徒增 CPU 开销，不会带来任何真实画质收益。
        if (result.length > targetBytes) {
            float minQ = 0.01f;
            float maxQ = 1.0f;
            for (int i = 0; i < QUALITY_SEARCH_ROUNDS; i++) {
                float midQ = (minQ + maxQ) / 2;
                byte[] temp = compress(image, midQ);
                if (temp.length <= targetBytes) {
                    result = temp;
                    minQ = midQ;
                } else {
                    maxQ = midQ;
                }
            }
        }

        // 2. 终极填充逻辑：通过追加 JPEG 备注信息填满最后的差额
        if (result.length < targetBytes) {
            long diff = targetBytes - result.length;
            log.info("精度修正：正在追加 {} 字节冗余数据以达到绝对精准", diff);
            
            // 创建新数组，复制原图并在末尾填充
            byte[] paddedResult = new byte[(int)targetBytes];
            System.arraycopy(result, 0, paddedResult, 0, result.length);
            
            // 注意：JPEG 以 FF D9 结尾，其后的数据会被大多数解码器忽略，非常安全
            // 我们在此处填充 0
            for (int i = result.length; i < targetBytes; i++) {
                paddedResult[i] = 0;
            }
            return paddedResult;
        }

        return result;
    }

    private byte[] compress(BufferedImage image, float quality) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Thumbnails.of(image)
                    .scale(1.0)
                    .outputFormat("jpg") 
                    .outputQuality(quality)
                    .toOutputStream(baos);
            return baos.toByteArray();
        }
    }
}
