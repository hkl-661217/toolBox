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
    private static final int MIN_TARGET_KB = 1;
    private static final int MAX_TARGET_KB = 10240;
    private static final int QUALITY_SEARCH_ROUNDS = 7;
    private static final int SCALE_SEARCH_ROUNDS = 4;
    private static final float PREFERRED_MIN_QUALITY = 0.35f;
    private static final float ABSOLUTE_MIN_QUALITY = 0.08f;
    private static final String OUTPUT_FORMAT = "jpg";

    public CompressionResult compressToTargetKB(MultipartFile file, Integer targetKB) throws IOException {
        validateInput(file, targetKB);

        long targetBytes = targetKB * 1024L;
        byte[] originalBytes = file.getBytes();
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(originalBytes));
        if (image == null) {
            throw new IOException("无法解析图片内容");
        }

        double minScale = resolveMinScale(image);
        EncodedImage result = findBestResult(image, targetBytes, minScale);

        if (result.bytes().length > targetBytes) {
            int suggestedKB = (int) Math.ceil(result.bytes().length / 1024.0);
            throw new IllegalArgumentException("目标过小，当前图片在极限压缩下最少约为 " + suggestedKB + "KB，请调大目标值后再试");
        }

        // 2. 终极填充逻辑：通过追加 JPEG 备注信息填满最后的差额
        if (result.bytes().length < targetBytes) {
            long diff = targetBytes - result.bytes().length;
            log.info("精度修正：正在追加 {} 字节冗余数据以达到绝对精准", diff);
            
            // 创建新数组，复制原图并在末尾填充
            byte[] paddedResult = new byte[(int)targetBytes];
            System.arraycopy(result.bytes(), 0, paddedResult, 0, result.bytes().length);
            
            // 注意：JPEG 以 FF D9 结尾，其后的数据会被大多数解码器忽略，非常安全
            // 我们在此处填充 0
            for (int i = result.bytes().length; i < targetBytes; i++) {
                paddedResult[i] = 0;
            }
            return new CompressionResult(
                    paddedResult,
                    image.getWidth(),
                    image.getHeight(),
                    result.width(),
                    result.height(),
                    result.scale(),
                    result.quality(),
                    diff,
                    OUTPUT_FORMAT
            );
        }

        return new CompressionResult(
                result.bytes(),
                image.getWidth(),
                image.getHeight(),
                result.width(),
                result.height(),
                result.scale(),
                result.quality(),
                0L,
                OUTPUT_FORMAT
        );
    }

    private void validateInput(MultipartFile file, Integer targetKB) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请先上传图片");
        }
        if (targetKB == null) {
            throw new IllegalArgumentException("请输入目标大小");
        }
        if (targetKB < MIN_TARGET_KB || targetKB > MAX_TARGET_KB) {
            throw new IllegalArgumentException("目标大小需在 " + MIN_TARGET_KB + "KB 到 " + MAX_TARGET_KB + "KB 之间");
        }
    }

    private EncodedImage findBestResult(BufferedImage image, long targetBytes, double minScale) throws IOException {
        EncodedImage preferredResult = findBestResultWithMinQuality(image, targetBytes, minScale, PREFERRED_MIN_QUALITY);
        if (preferredResult.bytes().length <= targetBytes) {
            return preferredResult;
        }

        return findBestResultWithMinQuality(image, targetBytes, minScale, ABSOLUTE_MIN_QUALITY);
    }

    private EncodedImage findBestResultWithMinQuality(BufferedImage image, long targetBytes, double minScale, float minQuality) throws IOException {
        EncodedImage fullScaleMinQualityResult = compress(image, 1.0d, minQuality);
        if (fullScaleMinQualityResult.bytes().length <= targetBytes) {
            return maximizeQualityAtScale(image, 1.0d, targetBytes, minQuality, fullScaleMinQualityResult);
        }

        double estimatedScale = estimateScale(1.0d, fullScaleMinQualityResult.bytes().length, targetBytes, minScale);
        EncodedImage estimatedScaleResult = compress(image, estimatedScale, minQuality);

        EncodedImage bestOversized = smallerOf(fullScaleMinQualityResult, estimatedScaleResult);
        if (estimatedScaleResult.bytes().length <= targetBytes) {
            return maximizeScaleAndQuality(image, targetBytes, minQuality, minScale, estimatedScale, 1.0d, estimatedScaleResult);
        }

        EncodedImage minScaleResult = compress(image, minScale, minQuality);
        if (minScaleResult.bytes().length > targetBytes) {
            return smallerOf(bestOversized, minScaleResult);
        }

        return maximizeScaleAndQuality(image, targetBytes, minQuality, minScale, minScale, estimatedScale, minScaleResult);
    }

    private double resolveMinScale(BufferedImage image) {
        double widthScale = 1.0d / Math.max(image.getWidth(), 1);
        double heightScale = 1.0d / Math.max(image.getHeight(), 1);
        return Math.max(widthScale, heightScale);
    }

    private EncodedImage maximizeScaleAndQuality(BufferedImage image, long targetBytes, float minQuality,
                                                 double minScale, double low, double high,
                                                 EncodedImage lowResult) throws IOException {
        double bestScale = Math.max(minScale, low);
        EncodedImage bestScaleResult = lowResult;

        for (int i = 0; i < SCALE_SEARCH_ROUNDS; i++) {
            double mid = (bestScale + high) / 2;
            EncodedImage midResult = compress(image, mid, minQuality);
            if (midResult.bytes().length <= targetBytes) {
                bestScale = mid;
                bestScaleResult = midResult;
            } else {
                high = mid;
            }
        }

        return maximizeQualityAtScale(image, bestScale, targetBytes, minQuality, bestScaleResult);
    }

    private EncodedImage maximizeQualityAtScale(BufferedImage image, double scale, long targetBytes,
                                                float minQuality, EncodedImage minQualityResult) throws IOException {
        EncodedImage bestResult = minQualityResult;
        float minQ = minQuality;
        float maxQ = 1.0f;

        for (int i = 0; i < QUALITY_SEARCH_ROUNDS; i++) {
            float midQ = (minQ + maxQ) / 2;
            EncodedImage temp = compress(image, scale, midQ);
            if (temp.bytes().length <= targetBytes) {
                bestResult = temp;
                minQ = midQ;
            } else {
                maxQ = midQ;
            }
        }

        return bestResult;
    }

    private double estimateScale(double currentScale, long currentBytes, long targetBytes, double minScale) {
        if (currentBytes <= 0 || targetBytes <= 0) {
            return minScale;
        }
        double estimated = currentScale * Math.sqrt(targetBytes / (double) currentBytes);
        estimated *= 0.98d;
        return Math.max(minScale, Math.min(1.0d, estimated));
    }

    private EncodedImage smallerOf(EncodedImage left, EncodedImage right) {
        return left.bytes().length <= right.bytes().length ? left : right;
    }

    private EncodedImage compress(BufferedImage image, double scale, float quality) throws IOException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            Thumbnails.of(image)
                    .scale(scale)
                    .imageType(BufferedImage.TYPE_INT_RGB)
                    .outputFormat(OUTPUT_FORMAT)
                    .outputQuality(quality)
                    .toOutputStream(baos);
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            return new EncodedImage(baos.toByteArray(), width, height, scale, quality);
        }
    }

    public record CompressionResult(
            byte[] bytes,
            int originalWidth,
            int originalHeight,
            int finalWidth,
            int finalHeight,
            double scale,
            float quality,
            long paddedBytes,
            String outputFormat
    ) {}

    private record EncodedImage(
            byte[] bytes,
            int width,
            int height,
            double scale,
            float quality
    ) {}
}
