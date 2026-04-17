package com.example.toolbox.controller;

import com.example.toolbox.service.CompressService;
import com.example.toolbox.service.CompressService.CompressionResult;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Controller
@RequestMapping("/api")
public class ImageCompressController {

    private final CompressService compressService;

    public ImageCompressController(CompressService compressService) {
        this.compressService = compressService;
    }

    /**
     * 核心接口：接收文件，直接返回压缩后的图片流
     */
    @PostMapping("/compress")
    @ResponseBody
    public ResponseEntity<?> compress(@RequestParam("file") MultipartFile file,
                                      @RequestParam("targetKB") Integer targetKB) {
        try {
            CompressionResult result = compressService.compressToTargetKB(file, targetKB);
            return ResponseEntity.ok()
                    .header(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                            "X-Original-Width,X-Original-Height,X-Final-Width,X-Final-Height,X-Scale,X-Quality,X-Padded-Bytes,X-Actual-Bytes")
                    .header("X-Original-Width", String.valueOf(result.originalWidth()))
                    .header("X-Original-Height", String.valueOf(result.originalHeight()))
                    .header("X-Final-Width", String.valueOf(result.finalWidth()))
                    .header("X-Final-Height", String.valueOf(result.finalHeight()))
                    .header("X-Scale", String.format("%.4f", result.scale()))
                    .header("X-Quality", String.format("%.4f", result.quality()))
                    .header("X-Padded-Bytes", String.valueOf(result.paddedBytes()))
                    .header("X-Actual-Bytes", String.valueOf(result.bytes().length))
                    .header("X-Output-Format", result.outputFormat())
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(result.bytes());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(e.getMessage());
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .contentType(MediaType.TEXT_PLAIN)
                    .body("压缩失败: " + e.getMessage());
        }
    }
}
