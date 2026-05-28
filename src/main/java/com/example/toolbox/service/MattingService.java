package com.example.toolbox.service;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

/**
 * AI 抠图：用 u2net_human_seg ONNX 模型算出人像前景的 alpha 蒙版。
 * 预处理与归一化方式对齐 rembg，保证抠图效果一致。
 */
@Service
public class MattingService implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(MattingService.class);

    private static final int INPUT_SIZE = 320;
    private static final float[] MEAN = {0.485f, 0.456f, 0.406f};
    private static final float[] STD = {0.229f, 0.224f, 0.225f};

    private final String modelPath;
    private OrtEnvironment env;
    private OrtSession session;
    private String inputName;

    public MattingService(@Value("${toolbox.idphoto.model-path:models/u2net_human_seg.onnx}") String modelPath) {
        this.modelPath = modelPath;
    }

    private synchronized void ensureLoaded() throws OrtException, IOException {
        if (session != null) {
            return;
        }
        Path path = Path.of(modelPath);
        if (!Files.exists(path)) {
            throw new IOException("抠图模型不存在: " + path.toAbsolutePath()
                    + "（请按 README 下载 u2net_human_seg.onnx 到 models/ 目录）");
        }
        env = OrtEnvironment.getEnvironment();
        session = env.createSession(path.toString(), new OrtSession.SessionOptions());
        inputName = session.getInputNames().iterator().next();
        log.info("抠图模型已加载: {} (input={})", path.toAbsolutePath(), inputName);
    }

    /**
     * 返回与原图同分辨率的前景 alpha 蒙版，取值 0..255（255=完全前景）。
     */
    public float[][] computeAlpha(BufferedImage image) throws OrtException, IOException {
        ensureLoaded();
        int ow = image.getWidth();
        int oh = image.getHeight();

        float[][][][] input = preprocess(image);
        try (OnnxTensor tensor = OnnxTensor.createTensor(env, input);
             OrtSession.Result result = session.run(Collections.singletonMap(inputName, tensor))) {
            float[][][][] output = (float[][][][]) result.get(0).getValue(); // [1][1][320][320]
            float[][] small = output[0][0];

            float min = Float.MAX_VALUE;
            float max = -Float.MAX_VALUE;
            for (float[] row : small) {
                for (float v : row) {
                    if (v < min) min = v;
                    if (v > max) max = v;
                }
            }
            float range = Math.max(max - min, 1e-6f);

            float[][] alpha = new float[oh][ow];
            for (int y = 0; y < oh; y++) {
                float sy = (y + 0.5f) * INPUT_SIZE / oh - 0.5f;
                int y0 = (int) Math.floor(sy);
                float fy = sy - y0;
                int y0c = clamp(y0, 0, INPUT_SIZE - 1);
                int y1c = clamp(y0 + 1, 0, INPUT_SIZE - 1);
                for (int x = 0; x < ow; x++) {
                    float sx = (x + 0.5f) * INPUT_SIZE / ow - 0.5f;
                    int x0 = (int) Math.floor(sx);
                    float fx = sx - x0;
                    int x0c = clamp(x0, 0, INPUT_SIZE - 1);
                    int x1c = clamp(x0 + 1, 0, INPUT_SIZE - 1);
                    float v00 = small[y0c][x0c];
                    float v01 = small[y0c][x1c];
                    float v10 = small[y1c][x0c];
                    float v11 = small[y1c][x1c];
                    float top = v00 + (v01 - v00) * fx;
                    float bot = v10 + (v11 - v10) * fx;
                    float v = top + (bot - top) * fy;
                    alpha[y][x] = (v - min) / range * 255f;
                }
            }
            return alpha;
        }
    }

    private float[][][][] preprocess(BufferedImage image) {
        BufferedImage resized = new BufferedImage(INPUT_SIZE, INPUT_SIZE, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(image, 0, 0, INPUT_SIZE, INPUT_SIZE, null);
        g.dispose();

        int[] rgb = resized.getRGB(0, 0, INPUT_SIZE, INPUT_SIZE, null, 0, INPUT_SIZE);

        // rembg 归一化：先除以图像中的最大像素值，再做 ImageNet 标准化
        float maxVal = 1e-6f;
        for (int p : rgb) {
            int r = (p >> 16) & 0xFF;
            int gg = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            if (r > maxVal) maxVal = r;
            if (gg > maxVal) maxVal = gg;
            if (b > maxVal) maxVal = b;
        }

        float[][][][] input = new float[1][3][INPUT_SIZE][INPUT_SIZE];
        for (int y = 0; y < INPUT_SIZE; y++) {
            for (int x = 0; x < INPUT_SIZE; x++) {
                int p = rgb[y * INPUT_SIZE + x];
                int r = (p >> 16) & 0xFF;
                int gg = (p >> 8) & 0xFF;
                int b = p & 0xFF;
                input[0][0][y][x] = (r / maxVal - MEAN[0]) / STD[0];
                input[0][1][y][x] = (gg / maxVal - MEAN[1]) / STD[1];
                input[0][2][y][x] = (b / maxVal - MEAN[2]) / STD[2];
            }
        }
        return input;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    @Override
    public void close() {
        try {
            if (session != null) {
                session.close();
            }
        } catch (Exception e) {
            log.warn("关闭抠图会话失败", e);
        }
    }
}
