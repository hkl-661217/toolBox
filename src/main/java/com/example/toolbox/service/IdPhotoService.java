package com.example.toolbox.service;

import com.example.toolbox.service.CompressService.CompressionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * 证件照流水线：AI抠人像 → 合成纯色底 → (可选)改尺寸/裁剪 → 卡KB(复用 CompressService) → JPEG。
 */
@Service
public class IdPhotoService {
    private static final Logger log = LoggerFactory.getLogger(IdPhotoService.class);

    // 边缘处理
    private static final int ERODE_RADIUS = 1;     // 蒙版往里收，去掉原底色残留的发丝白边
    private static final int FEATHER_RADIUS = 1;    // 羽化，让边缘过渡自然
    private static final int FG_THRESHOLD = 128;    // 判定前景的 alpha 阈值
    private static final float ALPHA_CONTRAST = 4.0f; // 收紧蒙版边缘，把宽的软过渡压成~1px，消除暗色光晕
    // 去残底（color decontamination）：把干净的前景色向半透明边缘扩散，
    // 覆盖被原背景污染的暗色，避免发丝/肩部出现原底色镶边。
    private static final int DECON_SOLID = 200;     // alpha 高于此视为“干净前景”，作为颜色来源
    private static final int DECON_GROW = 3;        // 前景色向外扩散的像素圈数

    // 质量告警阈值
    private static final double FG_RATIO_MIN = 0.15;
    private static final double FG_RATIO_MAX = 0.92;
    private static final double HEAD_COVERAGE_MIN = 0.30;
    private static final double SHOULDER_COVERAGE_MIN = 0.40;

    private final MattingService mattingService;
    private final CompressService compressService;

    public IdPhotoService(MattingService mattingService, CompressService compressService) {
        this.mattingService = mattingService;
        this.compressService = compressService;
    }

    public IdPhotoResult process(BufferedImage source, Color bgColor,
                                 Integer targetWidth, Integer targetHeight, Integer targetKB) throws Exception {
        int w = source.getWidth();
        int h = source.getHeight();

        float[][] alpha = mattingService.computeAlpha(source);

        List<String> warnings = detectWarnings(alpha, w, h);
        double fgRatio = foregroundRatio(alpha, w, h);

        float[][] sharp = sharpenAlpha(erode(alpha, w, h, ERODE_RADIUS), w, h, ALPHA_CONTRAST);
        int[] cleanRgb = decontaminate(source, sharp);
        float[][] processed = feather(sharp, w, h, FEATHER_RADIUS);
        BufferedImage composited = composite(cleanRgb, w, h, processed, bgColor);

        BufferedImage sized = composited;
        if (targetWidth != null && targetHeight != null) {
            sized = coverCrop(composited, processed, targetWidth, targetHeight);
        }

        CompressionResult compressed = compressService.compressToTargetKB(sized, targetKB);
        log.info("证件照处理完成: {}x{} -> 实际 {}KB, 前景占比 {}%, 告警 {}",
                compressed.finalWidth(), compressed.finalHeight(),
                compressed.bytes().length / 1024, Math.round(fgRatio * 100), warnings.size());
        return new IdPhotoResult(compressed, fgRatio, warnings);
    }

    /**
     * 去残底：把 alpha 高的“干净前景色”逐圈向外扩散到半透明边缘，
     * 覆盖被原背景污染的颜色，使边缘合成时从干净前景过渡到新底色。
     */
    private int[] decontaminate(BufferedImage src, float[][] alpha) {
        int w = src.getWidth();
        int h = src.getHeight();
        int[] rgb = src.getRGB(0, 0, w, h, null, 0, w);
        boolean[] solid = new boolean[w * h];
        for (int i = 0; i < rgb.length; i++) {
            solid[i] = alpha[i / w][i % w] >= DECON_SOLID;
        }
        for (int iter = 0; iter < DECON_GROW; iter++) {
            int[] nextRgb = rgb.clone();
            boolean[] nextSolid = solid.clone();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int idx = y * w + x;
                    if (solid[idx] || alpha[y][x] <= 0f) {
                        continue;
                    }
                    long r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int dy = -1; dy <= 1; dy++) {
                        int yy = y + dy;
                        if (yy < 0 || yy >= h) {
                            continue;
                        }
                        for (int dx = -1; dx <= 1; dx++) {
                            int xx = x + dx;
                            if (xx < 0 || xx >= w) {
                                continue;
                            }
                            int nidx = yy * w + xx;
                            if (solid[nidx]) {
                                int p = rgb[nidx];
                                r += (p >> 16) & 0xFF;
                                g += (p >> 8) & 0xFF;
                                b += p & 0xFF;
                                n++;
                            }
                        }
                    }
                    if (n > 0) {
                        nextRgb[idx] = ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (b / n);
                        nextSolid[idx] = true;
                    }
                }
            }
            rgb = nextRgb;
            solid = nextSolid;
        }
        return rgb;
    }

    private BufferedImage composite(int[] srcRgb, int w, int h, float[][] alpha, Color bg) {
        int br = bg.getRed();
        int bgrn = bg.getGreen();
        int bb = bg.getBlue();
        int[] out = new int[w * h];
        for (int i = 0; i < out.length; i++) {
            float a = alpha[i / w][i % w] / 255f;
            if (a <= 0f) {
                out[i] = (br << 16) | (bgrn << 8) | bb;
                continue;
            }
            if (a >= 1f) {
                out[i] = srcRgb[i] & 0xFFFFFF;
                continue;
            }
            int p = srcRgb[i];
            int r = (p >> 16) & 0xFF;
            int gg = (p >> 8) & 0xFF;
            int b = p & 0xFF;
            int rr = Math.round(r * a + br * (1 - a));
            int gr = Math.round(gg * a + bgrn * (1 - a));
            int brr = Math.round(b * a + bb * (1 - a));
            out[i] = (rr << 16) | (gr << 8) | brr;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return img;
    }

    /**
     * 缩放铺满目标框后裁剪：水平居中于前景，垂直保留头顶（留约 8% 头部空间）。
     */
    private BufferedImage coverCrop(BufferedImage img, float[][] alpha, int tw, int th) {
        int w = img.getWidth();
        int h = img.getHeight();
        double scale = Math.max((double) tw / w, (double) th / h);
        int sw = Math.max(tw, (int) Math.ceil(w * scale));
        int sh = Math.max(th, (int) Math.ceil(h * scale));

        BufferedImage scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, sw, sh, null);
        g.dispose();

        int[] box = foregroundBox(alpha, w, h); // {top, centerX} 原图坐标，或 null
        int xOff;
        int yOff;
        if (box != null) {
            int centerXScaled = (int) Math.round(box[1] * scale);
            xOff = centerXScaled - tw / 2;
            int topScaled = (int) Math.round(box[0] * scale);
            int headroom = (int) Math.round(th * 0.08);
            yOff = topScaled - headroom;
        } else {
            xOff = (sw - tw) / 2;
            yOff = (sh - th) / 2;
        }
        xOff = clamp(xOff, 0, sw - tw);
        yOff = clamp(yOff, 0, sh - th);

        BufferedImage out = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = out.createGraphics();
        g2.drawImage(scaled, -xOff, -yOff, null);
        g2.dispose();
        return out;
    }

    private float[][] sharpenAlpha(float[][] a, int w, int h, float contrast) {
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float v = (a[y][x] / 255f - 0.5f) * contrast + 0.5f;
                if (v < 0f) {
                    v = 0f;
                } else if (v > 1f) {
                    v = 1f;
                }
                out[y][x] = v * 255f;
            }
        }
        return out;
    }

    private float[][] erode(float[][] a, int w, int h, int r) {
        if (r <= 0) {
            return a;
        }
        float[][] out = new float[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float m = 255f;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = clamp(y + dy, 0, h - 1);
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = clamp(x + dx, 0, w - 1);
                        if (a[yy][xx] < m) {
                            m = a[yy][xx];
                        }
                    }
                }
                out[y][x] = m;
            }
        }
        return out;
    }

    private float[][] feather(float[][] a, int w, int h, int r) {
        if (r <= 0) {
            return a;
        }
        float[][] out = new float[h][w];
        int area = (2 * r + 1) * (2 * r + 1);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float s = 0f;
                for (int dy = -r; dy <= r; dy++) {
                    int yy = clamp(y + dy, 0, h - 1);
                    for (int dx = -r; dx <= r; dx++) {
                        int xx = clamp(x + dx, 0, w - 1);
                        s += a[yy][xx];
                    }
                }
                out[y][x] = s / area;
            }
        }
        return out;
    }

    private double foregroundRatio(float[][] a, int w, int h) {
        long fg = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (a[y][x] >= FG_THRESHOLD) {
                    fg++;
                }
            }
        }
        return (double) fg / ((long) w * h);
    }

    /**
     * 前景外接框信息：返回 {最高前景行, 前景水平中心列}；无前景返回 null。
     */
    private int[] foregroundBox(float[][] a, int w, int h) {
        int top = Integer.MAX_VALUE;
        long sumX = 0;
        long count = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (a[y][x] >= FG_THRESHOLD) {
                    if (y < top) {
                        top = y;
                    }
                    sumX += x;
                    count++;
                }
            }
        }
        if (count == 0) {
            return null;
        }
        return new int[]{top, (int) (sumX / count)};
    }

    private List<String> detectWarnings(float[][] a, int w, int h) {
        List<String> warnings = new ArrayList<>();
        double ratio = foregroundRatio(a, w, h);
        if (ratio < FG_RATIO_MIN) {
            warnings.add(String.format("前景占比过低(%.0f%%)，人像可能被误删，建议人工复核", ratio * 100));
        } else if (ratio > FG_RATIO_MAX) {
            warnings.add(String.format("前景占比过高(%.0f%%)，背景可能未被去除，建议人工复核", ratio * 100));
        }

        double headCov = bandCoverage(a, w, h, 0.00, 0.35, 0.30, 0.70);
        double shoulderCov = bandCoverage(a, w, h, 0.60, 0.95, 0.20, 0.80);
        if (headCov >= HEAD_COVERAGE_MIN && shoulderCov < SHOULDER_COVERAGE_MIN) {
            warnings.add(String.format("肩部/身体区域覆盖偏低(%.0f%%)，可能把浅色衣物当背景删除，建议人工复核",
                    shoulderCov * 100));
        }
        return warnings;
    }

    private double bandCoverage(float[][] a, int w, int h,
                                double rowStart, double rowEnd, double colStart, double colEnd) {
        int y0 = (int) (h * rowStart);
        int y1 = (int) (h * rowEnd);
        int x0 = (int) (w * colStart);
        int x1 = (int) (w * colEnd);
        long fg = 0;
        long total = 0;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                total++;
                if (a[y][x] >= FG_THRESHOLD) {
                    fg++;
                }
            }
        }
        return total == 0 ? 0 : (double) fg / total;
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return v < lo ? lo : (v > hi ? hi : v);
    }

    public record IdPhotoResult(CompressionResult compression, double foregroundRatio, List<String> warnings) {
    }
}
