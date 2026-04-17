package com.example.toolbox;

import com.example.toolbox.service.CompressService;
import org.springframework.boot.CommandLineRunner;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;

@Component
public class CompressionBenchmark implements CommandLineRunner {

    private final CompressService compressService;

    public CompressionBenchmark(CompressService compressService) {
        this.compressService = compressService;
    }

    @Override
    public void run(String... args) throws Exception {
        // 只在带参数 --benchmark 时运行
        boolean runBenchmark = false;
        for(String arg : args) if(arg.equals("--benchmark")) runBenchmark = true;
        if(!runBenchmark) return;

        String path = "/Users/huangkailun/Desktop/IMG_1541.png";
        byte[] content = Files.readAllBytes(Paths.get(path));
        MockMultipartFile mockFile = new MockMultipartFile("file", "IMG_1541.png", "image/png", content);

        System.out.println("\n--- 🚀 开始压缩效率基准测试 ---");
        System.out.println("档位(KB)\t实际大小(Bytes)\t耗时(ms)");

        for (int targetKB = 200; targetKB <= 7000; targetKB += 200) {
            long start = System.currentTimeMillis();
            byte[] result = compressService.compressToTargetKB(mockFile, targetKB);
            long end = System.currentTimeMillis();
            
            System.out.printf("%d\t%d\t%d\n", targetKB, result.length, (end - start));
        }
        System.out.println("--- ✅ 测试结束 ---\n");
        System.exit(0);
    }
}