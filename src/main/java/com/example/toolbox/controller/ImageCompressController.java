package com.example.toolbox.controller;

import com.example.toolbox.service.CompressService;
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
    public ResponseEntity<byte[]> compress(@RequestParam("file") MultipartFile file, 
                                          @RequestParam("targetKB") Integer targetKB) throws IOException {
        byte[] result = compressService.compressToTargetKB(file, targetKB);
        
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .body(result);
    }
}