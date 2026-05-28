# 证件照换底色 + 改尺寸 + 卡KB（toolBox 新增功能）

在原有「图片精确压缩到指定 KB」工具上，新增证件照处理流水线：

```
上传原图 → ①AI抠人像 → ②换纯色底 → ③(可选)改尺寸/裁剪 → ④卡KB(复用原压缩逻辑) → 输出JPEG
```

顺序固定为：**先换底 → 再改尺寸 → 最后卡KB**。

抠图用本地 ONNX 模型（u2net_human_seg），**离线、免费、纯 Java**，不依赖 Python。

---

## 一、环境与安装

### 1. JDK
需 **JDK 21**。本机已装在：
`/Users/huangkailun/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home`

> 注意：本机 Homebrew 的 `mvn` 默认跑在 JDK 25 上，构建/运行请显式指定 JDK 21：
> ```bash
> export JAVA_HOME=/Users/huangkailun/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
> ```

### 2. 抠图模型（约 168MB，不进 git）
首次使用前下载模型到 `models/` 目录：

```bash
mkdir -p models
curl -L -o models/u2net_human_seg.onnx \
  https://github.com/danielgatis/rembg/releases/download/v0.0.0/u2net_human_seg.onnx
```

模型路径可在 `application.properties` 配置（默认即下方值）：
```properties
toolbox.idphoto.model-path=models/u2net_human_seg.onnx
```

### 3. 依赖
`pom.xml` 已新增 ONNX Runtime（自带 macOS arm64 原生库，CPU 推理，无需额外安装）：
```xml
<dependency>
    <groupId>com.microsoft.onnxruntime</groupId>
    <artifactId>onnxruntime</artifactId>
    <version>1.20.0</version>
</dependency>
```

---

## 二、运行与使用

### 启动
```bash
export JAVA_HOME=/Users/huangkailun/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
mvn spring-boot:run
```

### 测试页（不带支付墙，用于验证效果）
浏览器打开： **http://localhost:8080/idphoto.html**
- 支持一键预设 / 手动填底色、尺寸、目标KB
- 展示原图 vs 处理后、输出尺寸、实际KB、前景占比、质量告警

### API
```
POST /api/idphoto   (multipart/form-data)
```
| 参数 | 必填 | 说明 | 示例 |
|------|------|------|------|
| `file` | 是 | 原始证件照 | 文件 |
| `bg` | 是 | 底色，支持 hex 或 RGB | `#FF0000` 或 `255,0,0` |
| `size` | 否 | 目标尺寸 `宽x高`，**留空=只换底、保持原尺寸** | `295x413` |
| `targetKB` | 是 | 目标大小(KB) | `45` |

curl 示例：
```bash
curl -F "file=@in.jpg" -F "bg=#FF0000" -F "size=295x413" -F "targetKB=45" \
  http://localhost:8080/api/idphoto -o out.jpg
```

响应为 JPEG 图片，附带元数据响应头：

| 响应头 | 含义 |
|--------|------|
| `X-Final-Width` / `X-Final-Height` | 输出像素尺寸 |
| `X-Actual-Bytes` | 实际字节数 |
| `X-Foreground-Ratio` | 前景（人像）占比，0~1 |
| `X-Warning` | 质量告警（URL 编码的中文，前端 `decodeURIComponent` 解码；为空表示无告警） |

---

## 三、常见底色 / 尺寸参考

**底色（hex）：**
| 颜色 | hex | RGB |
|------|-----|-----|
| 白底 | `#FFFFFF` | 255,255,255 |
| 红底 | `#FF0000` | 255,0,0 |
| 蓝底 | `#0000FF` | 0,0,255 |
| 浅蓝 | `#00BFF3` | 0,191,243 |

**尺寸（像素）：**
| 规格 | 宽×高 |
|------|-------|
| 一寸 | 295×413 |
| 二寸 | 413×579 |
| 小一寸 | 260×378 |
| 大一寸 | 390×567 |

**常见考试预设（仅为结构示例，⚠️ 各系统底色/尺寸/KB 每年、各省份不同，务必以当年官方报名系统的实际要求为准）：**
| 考试 | 底色 | 尺寸 | 目标KB（示例） |
|------|------|------|------|
| 教师资格 | 白底 | 一寸 295×413 | ≤190KB |
| 国家公务员 | 白底 | 一寸 295×413 | ≤30KB |
| 事业编 | 红底 | 一寸 295×413 | ≤45KB |
| 医师资格 | 白底 | 413×531 | ≤40KB |
| 军队文职 | 蓝底 | 一寸 295×413 | ≤100KB |

> 预设值可在 `idphoto.html` 顶部的 `examPresets` 数组里按你核实过的真实要求修改。

---

## 四、质量与已知限制

- **边缘处理**：蒙版依次做 erode(内收去原底残留) → alpha 锐化(把宽的软过渡压成~1px，消除暗色光晕) → 去残底(decontaminate，干净前景色向边缘扩散) → 1px 羽化抗锯齿。白底下深色头发边缘已较干净；如想更利落/更柔和，调 `IdPhotoService` 里的 `ALPHA_CONTRAST` 与 `ERODE_RADIUS` 即可。
- **质量告警**：前景占比 <15% 或 >92%、或「头部正常但肩部覆盖过低」（疑似白衣白底误抠）时，会在 `X-Warning` 返回提示，但**仍照常出图**，供人工复核。阈值定义在 `IdPhotoService` 顶部常量，可调。
- **裁剪策略**：填了尺寸时按「缩放铺满 + 水平居中于前景 + 垂直保留头顶约8%」裁剪。
- **EXIF 旋转**：当前不自动按手机照片 EXIF 方向旋转，竖拍照片若显示侧躺需先转正（后续可加）。
- **未接入支付墙**：本功能目前在独立测试页 `idphoto.html`，未动现有 `index.html`（带 4.9 元解锁的主页面）。确认效果满意后再决定是否并入主流程。
