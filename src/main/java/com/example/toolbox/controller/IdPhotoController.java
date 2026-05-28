package com.example.toolbox.controller;

import com.example.toolbox.service.CompressService.CompressionResult;
import com.example.toolbox.service.IdPhotoService;
import com.example.toolbox.service.IdPhotoService.IdPhotoResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Controller
@RequestMapping("/api")
public class IdPhotoController {

    private static final Logger log = LoggerFactory.getLogger(IdPhotoController.class);

    private final IdPhotoService idPhotoService;

    public IdPhotoController(IdPhotoService idPhotoService) {
        this.idPhotoService = idPhotoService;
    }

    /**
     * 证件照换底 + 改尺寸 + 卡KB 一体化接口。
     * 参数：file 原图；bg 底色(#FF0000 或 255,0,0)；size 可选目标尺寸(295x413)；targetKB 目标大小。
     */
    @PostMapping("/idphoto")
    @ResponseBody
    public ResponseEntity<?> idphoto(@RequestParam("file") MultipartFile file,
                                     @RequestParam("bg") String bg,
                                     @RequestParam(value = "size", required = false) String size,
                                     @RequestParam("targetKB") Integer targetKB) {
        try {
            if (file == null || file.isEmpty()) {
                return badRequest("请先上传图片");
            }
            Color color = parseColor(bg);
            int[] wh = parseSize(size);

            BufferedImage source = ImageIO.read(new ByteArrayInputStream(file.getBytes()));
            if (source == null) {
                return badRequest("无法解析图片内容");
            }

            IdPhotoResult result = idPhotoService.process(
                    source, color,
                    wh == null ? null : wh[0],
                    wh == null ? null : wh[1],
                    targetKB);
            CompressionResult c = result.compression();
            String warning = String.join(" | ", result.warnings());

            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                            "X-Final-Width,X-Final-Height,X-Actual-Bytes,X-Foreground-Ratio,X-Warning,X-Output-Format")
                    .header("X-Final-Width", String.valueOf(c.finalWidth()))
                    .header("X-Final-Height", String.valueOf(c.finalHeight()))
                    .header("X-Actual-Bytes", String.valueOf(c.bytes().length))
                    .header("X-Foreground-Ratio", String.format("%.4f", result.foregroundRatio()))
                    .header("X-Warning", URLEncoder.encode(warning, StandardCharsets.UTF_8))
                    .header("X-Output-Format", c.outputFormat())
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(c.bytes());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("证件照处理失败", e);
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("处理失败: " + e.getMessage());
        }
    }

    private ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().contentType(MediaType.TEXT_PLAIN).body(message);
    }

    static Color parseColor(String s) {
        if (s == null || s.isBlank()) {
            throw new IllegalArgumentException("请提供底色 bg，例如 #FF0000 或 255,0,0");
        }
        String v = s.trim();
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        if (v.matches("[0-9a-fA-F]{6}")) {
            return new Color(
                    Integer.parseInt(v.substring(0, 2), 16),
                    Integer.parseInt(v.substring(2, 4), 16),
                    Integer.parseInt(v.substring(4, 6), 16));
        }
        if (v.matches("\\d{1,3}\\s*,\\s*\\d{1,3}\\s*,\\s*\\d{1,3}")) {
            String[] p = v.split(",");
            return new Color(
                    clampByte(Integer.parseInt(p[0].trim())),
                    clampByte(Integer.parseInt(p[1].trim())),
                    clampByte(Integer.parseInt(p[2].trim())));
        }
        throw new IllegalArgumentException("底色格式不支持，请用 #FF0000 或 255,0,0");
    }

    static int[] parseSize(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String v = s.trim().toLowerCase().replace('*', 'x').replace('×', 'x').replace(',', 'x');
        String[] p = v.split("x");
        if (p.length != 2) {
            throw new IllegalArgumentException("尺寸格式应为 宽x高，例如 295x413");
        }
        try {
            int w = Integer.parseInt(p[0].trim());
            int h = Integer.parseInt(p[1].trim());
            if (w < 1 || h < 1 || w > 6000 || h > 6000) {
                throw new IllegalArgumentException("尺寸需在 1~6000 像素之间");
            }
            return new int[]{w, h};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("尺寸格式应为 宽x高，例如 295x413");
        }
    }

    private static int clampByte(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
