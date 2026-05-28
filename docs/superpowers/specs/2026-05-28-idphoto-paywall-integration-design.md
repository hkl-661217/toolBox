# 证件照换底接入付费产品 — 设计文档

日期：2026-05-28

## 背景

后端的证件照「换底 + 改尺寸 + 卡KB」流水线（`/api/idphoto`）已实现并实测可用。
但它目前只有一个独立的内部测试页 `idphoto.html`（无付费墙），未接入面向客户的
`index.html`。客户打开主页看到的仍只有图片压缩功能 + 解锁码付费墙。

目标：把换底做成客户**自助付费**功能，并保留**人工兜底**入口（抠图不满意可转人工）。

## 现状关键事实

- `index.html`：Vue 3 + Element Plus 单页应用。压缩流程 = 上传 → `/api/compress` →
  模糊预览 + 详情 → 输入 6 位解锁码（`/api/verify`）→ 解锁下载。
- 付费墙机制：`isPaid` / `verifyCode` / `previewUrl`(模糊) / `unlockedUrl`(原图) /
  `createLockedPreview()` / `downloadFile()` / `verifyAndUnlock()`。
- 解锁码模型：`/api/verify` 校验全局通用码池（`toolbox.active-codes` 配置），
  **不绑定具体商品** → 一个解锁码对压缩和换底都通用。
- `idphoto.html`：独立测试页，无付费墙，含考试预设。保留不动，作内部工具。
- 后端 `/api/idphoto` 已就绪：返回 JPEG blob + 响应头
  `X-Final-Width`/`X-Final-Height`/`X-Actual-Bytes`/`X-Foreground-Ratio`/
  `X-Warning`/`X-Output-Format`。

## 方案（已选）

**顶部 Tab 切换**：在 `index.html` 同一个 Vue 卡片内加「图片压缩 / 证件照换底」两个
tab，复用同一套付费墙弹窗与解锁码流程。

## 详细设计

### UI 结构
- 主卡片标题下方加 tab 切换（Element Plus `el-tabs` 或简单按钮组），状态 `activeTab`
  取值 `compress` | `idphoto`，默认 `compress`。
- `compress` tab：现有压缩表单，**完全不动**。
- `idphoto` tab：新换底表单
  - 文件上传（复用上传区样式）
  - 底色输入（文本 `#FF0000` 或 `255,0,0`）+ 底色快捷 chips（红/蓝/白/浅蓝）
  - 输出尺寸输入（`295x413`，留空=不改尺寸）+ 尺寸快捷 chips（一寸/二寸/小一寸/大一寸）
  - 目标 KB 输入
  - 考试一键预设（搬 idphoto.html）：教资白底190KB / 国考白底30KB /
    事业编红底45KB / 医师白底40KB / 军队蓝底100KB
  - 「生成证件照」按钮

### 新增 Vue 状态
- `activeTab`（默认 `'compress'`）
- `idBg`（默认 `'#FF0000'`）、`idSize`（默认 `'295x413'`）、`idKB`（默认 `45`）
- `idMeta`（换底结果元信息：finalWidth/finalHeight/actualKB/foregroundRatio/warning）

### 新增 / 复用函数
- 新增 `submitIdPhoto()`：
  - 校验已选文件
  - `FormData`: `file`、`bg=idBg`、`size=idSize`(非空才加)、`targetKB=idKB`
  - `POST /api/idphoto`，`responseType: 'blob'`
  - 读响应头填 `idMeta`，`X-Warning` 做 `decodeURIComponent`
  - 复用 `blobToDataUrl` 存 `unlockedUrl` + localStorage 缓存
  - 复用 `createLockedPreview` 生成模糊预览 → `previewUrl`
  - `visible=true`，`isPaid=false`
- 复用（不改）：付费墙 dialog、`isPaid`/`verifyCode`/`verifyAndUnlock`、
  `previewUrl`/`unlockedUrl`/`downloadFile`/`goToXianyu`、localStorage 缓存逻辑。

### 数据流
与压缩一致：提交 → 后端返回 JPEG + headers → 模糊预览 + 详情/告警 → 输入解锁码
→ `verifyAndUnlock` 成功 → 清晰预览 + 下载。换底详情额外展示前景占比与告警。

### 付费墙弹窗适配
- 弹窗的「压缩详情」区按 `activeTab` 展示对应字段：
  - 压缩：现有字段不变
  - 换底：输出尺寸、实际大小、前景占比、告警文案
- 复用模糊预览 + 解锁码 + 下载，逻辑不变。

### 转人工入口
- 位置：换底结果弹窗内，按钮「抠图不满意？转人工处理」。
- 行为：复用 `goToXianyu`，但**指向通用兜底 SKU**，需把转人工用的口令/链接独立成
  常量（不污染压缩用的 `kouling`/`shortUrl`/`itemId`）：
  - `idKouling` = `【闲鱼】https://m.tb.cn/h.RU0kTxN?tk=ISRQ5CiiNej HU287 「我在闲鱼发布了【报名照审核不过修改 50KB急单包过】」点击链接直接打开`
  - `idShortUrl` = `https://m.tb.cn/h.RU0kTxN`
  - `idItemId` = `1054905117302`
  - 把 `goToXianyu` 参数化（接收口令/短链/itemId），压缩与换底各传各的。
- 高亮逻辑：当 `idMeta.warning` 非空（前景过低/可能误删）时，把转人工按钮设为主色/醒目，
  并在告警文案后追加一句"可点下方转人工"。

### 错误与告警处理
- 换底失败：显示后端返回的中文错误文本（如"目标过小"/"无法解析图片"/"抠图模型不存在"）。
- 告警（非阻断）：`X-Warning` 内容显示在预览区，提示转人工。

## 不做（YAGNI）
- 不改后端（接口已就绪）。
- 不改解锁码模型（保持全局通用码）。
- 不给换底单独计费（与压缩共用解锁码）。
- 不动 `idphoto.html`（保留作内部测试页）。

## 验收标准
1. 主页有「图片压缩 / 证件照换底」tab，默认压缩，压缩流程零回归。
2. 换底 tab：选图 + 选底色/尺寸/KB（或点考试预设）→ 生成 → 模糊预览 + 详情/告警。
3. 未解锁不可下载清晰图；输入有效解锁码后可下载（与压缩同一套码）。
4. 换底结果弹窗有转人工按钮，点击复制通用兜底款淘口令并跳转闲鱼；告警时按钮高亮。
5. 移动端布局不破。

## 实现影响范围
- 仅改 `src/main/resources/static/index.html`（前端单文件）。
- 后端、`idphoto.html`、解锁码配置均不改。
- 部署提醒：改的是 static 资源，需重新构建/重启在跑的实例（或依赖 devtools 热加载）才生效。
