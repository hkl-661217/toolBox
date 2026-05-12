# 高优三项修复设计稿（2026-05-12）

针对 MSC Shipping Tracking 项目最先解决的三个高优先级风险：

1. SMTP 授权码明文存储 → AES-GCM 加密
2. 邮件失败无重试 → 独立重试任务
3. `batch-limit=5` 死上限 → 按"距上次查询超过 N 小时"轮转

附带顺手修：`ShippingTrackingScheduler` 用 `System.err.printf` 而非 SLF4J 的问题（高优 #6）。

---

## Item #1 — SMTP 授权码加密

### 目标
H2 数据文件被非授权读取时，`shipping_tracking_notification_account.smtp_password` 不再直接暴露 QQ 邮箱授权码。

### 算法与密钥
- **算法**：AES/GCM/NoPadding，256-bit key，12-byte IV per encrypt。GCM 自带认证标签，密文被改动 → 解密阶段抛异常，避免静默错误。
- **密钥来源**：环境变量 `SHIPPING_TRACKING_ENCRYPTION_KEY`，值为 base64 编码的 32 字节随机串。
  - 与现有 `QQ_MAIL_USERNAME` / `QQ_MAIL_AUTH_CODE` 的部署习惯一致
  - 启动脚本（`scripts/...` 或 systemd unit）多 export 一个变量即可

### 存储格式
列值复用现有 `smtp_password varchar(300)`，**不改 schema**。值格式：

```
v1:<base64(iv,12B)>:<base64(ciphertext+tag)>
```

- 前缀 `v1:` 是版本号，留给将来 v2 密钥轮换或换算法用
- IV 每次加密随机生成，与密文拼接存储

### 兼容性 / 迁移
不做大爆破式迁移：

- **读取**：检测前缀。`v1:` → 走解密；其他（含纯明文）→ 视为旧值，原样返回。
- **写入**：永远加密（含新建 + update）。
- 结果：旧明文行在下一次 update 时自然变成密文；当前已有的几行明文继续可用。

### 启动自检
应用启动时：

```
SELECT count(*) FROM shipping_tracking_notification_account WHERE smtp_password LIKE 'v1:%'
```

- 若 count > 0 且 env 中 `SHIPPING_TRACKING_ENCRYPTION_KEY` 为空 → 抛 `IllegalStateException` 让 Spring 启动失败。
- 这避免了"密钥被运维忘记 export，但库里有密文，应用静默把 `v1:...` 当明文发给 QQ SMTP"这种灾难。

### 新增 / 改动文件
- 新：`src/main/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoService.java`
  - 公开方法：`String encrypt(String plaintext)`、`String decryptIfNeeded(String stored)`
  - `@PostConstruct` 做启动自检
- 改：`src/main/java/com/example/myaiproject/shipping/repo/NotificationAccountRepository.java`
  - `insert(...)` 写前调用 `crypto.encrypt(...)`
  - `findAll/findEnabled/findById` 的 `RowMapper` 读出后调用 `crypto.decryptIfNeeded(...)`
- 改：`src/main/java/com/example/myaiproject/shipping/service/NotificationAccountService.java`
  - 无需逻辑变更，但传入 `smtpPassword` 仍是明文（service 层不感知加密）

### 测试
新增 `src/test/java/com/example/myaiproject/shipping/support/ShippingTrackingCryptoServiceTest.java`：

1. round-trip：encrypt → decryptIfNeeded → 等于原文
2. 旧明文兜底：`decryptIfNeeded("plain-old-value")` → 返回原值不抛异常
3. 缺密钥时启动失败：模拟库里有 `v1:` 行 + env 未设 → `@PostConstruct` 抛异常
4. 错误密文（截断 / 改字节）→ `decryptIfNeeded` 抛 `GeneralSecurityException` 或包装异常，且消息中不含密钥

Repository 集成测试（已有 `NotificationAccountToggleTest` 可扩展，或新增）：

5. 新建账号 → 库里 `smtp_password` 是 `v1:` 开头 + 不等于原值

---

## Item #2 — 邮件重试

### 目标
SMTP 暂时不可用（QQ 限流、网络抖动）时，变更通知不丢。

### Schema 变更
向 `shipping_tracking_change_log` 表追加两列：

```sql
alter table shipping_tracking_change_log
  add column if not exists retry_count int not null default 0;
alter table shipping_tracking_change_log
  add column if not exists last_retry_at timestamp with time zone;

comment on column shipping_tracking_change_log.retry_count
  is '邮件重发次数，含首次发送（首次失败计为 0，每次补偿 +1）';
comment on column shipping_tracking_change_log.last_retry_at
  is '最近一次重发尝试时间（含失败）';
```

`schema.sql` 用 `add column if not exists` 保持幂等。

### 新组件
`src/main/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJob.java`

- `@Service` + `@Scheduled(cron = "${shipping.tracking.retry-cron:0 */15 * * * *}")`
- 步骤：
  1. 调用新仓库方法 `ShippingTrackingChangeLogRepository.findPendingRetries(maxAttempts, maxAgeHours)`，返回 `email_sent=false AND retry_count < maxAttempts AND created_at > now() - maxAgeHours h` 的行。
  2. 对每行：
     - 加载关联 `ShippingTrackingBinding`（用 `binding_id`）
     - 反序列化 `before_json` / `after_json` 拼出 `List<ShippingTrackingEventChange>`
     - 调 `ShippingTrackingEmailTemplateBuilder.buildChangeNotification(...)` 重建邮件
     - 按当前 `NotificationAccountService.listEnabled()` 走相同的多账号 / 全局回退分发逻辑
     - 任一账号发送成功 → `update change_log set email_sent=true, email_sent_time=?, retry_count=retry_count+1, last_retry_at=? where id=?`
     - 全部失败 → `update change_log set retry_count=retry_count+1, last_retry_at=? where id=?`
  3. 不在循环里 sleep（重试都是已知失败的少量行，发送本身有 SMTP 超时）

### 配置
`application.properties` 新增：

```properties
shipping.tracking.retry-cron=0 */15 * * * *
shipping.tracking.retry-max-attempts=6
shipping.tracking.retry-max-age-hours=24
```

`ShippingTrackingProperties` 加三个对应字段。

### 关键判断
- **重试只对那一封原邮件**：用 `before_json/after_json` 重建邮件正文，不去 MSC 重新查。期间发生的新变更走它自己的 change_log + 独立邮件。这是为了"通知是变更的副作用，重试不应改变变更内容"。
- **多账号策略**：与首次发送一致（先 `listEnabled()` 走每账号自发自；空 → 回退全局 `notify-emails`）。
- **email 模板复用**：现有 `ShippingTrackingEmailTemplateBuilder.buildChangeNotification(binding, eta, latestNode, changes, now)` 已经接受这些参数；其中 `eta` 和 `latestNode` 取**首次记录时的值**（从最新 snapshot 反查 or 直接从 binding 当前值都可以；选择**用 binding 当前值**，因为重试时这两个字段对邮件主体可读性影响小）。
- **顺序无关**：重试任务和日常调度可同时跑，不会冲突（操作不同的行）。

### 重构 ShippingTrackingService
首次发送的逻辑也要走"成功落 `retry_count` 字段"的形态，保持两条路径写出来的行是一种 schema：

- 首次发送成功 → insert change_log with `email_sent=true, retry_count=0, email_sent_time=now`
- 首次发送失败 → insert change_log with `email_sent=false, retry_count=0`，待重试任务接管

当前实现已经接近这个语义（只是没有 `retry_count`），改动量小。

### 测试
新增 `src/test/java/com/example/myaiproject/shipping/service/ShippingTrackingNotificationRetryJobTest.java`：

1. 扫到失败行 → 调用 mock sender 成功 → 行被标记 `email_sent=true`、`retry_count=1`
2. 扫到失败行 → mock sender 失败 → `email_sent=false`、`retry_count=1`、`last_retry_at` 设置
3. `retry_count=6` 的行不被扫到
4. `created_at` 早于 24h 的行不被扫到
5. 邮件正文反序列化：构造 before/after JSON → 与首次发送渲染出的邮件 HTML 完全一致（或 diff 只在时间戳）

---

## Item #3 — 调度策略

### 目标
让所有 enabled binding 都能被刷到，不被固定的 `batch-limit=5` 截断。

### 仓库变更
`ShippingTrackingBindingRepository` 新增方法：

```java
public List<ShippingTrackingBinding> findBindingsDueForQuery(OffsetDateTime threshold) {
    return jdbcTemplate.query("""
            select * from shipping_tracking_binding
            where enabled = true
              and (last_query_time is null or last_query_time < ?)
            order by last_query_time asc nulls first
            """, mapper(), threshold);
}
```

- `nulls first` 让从未查过的 binding 优先（新增的）
- `asc` 让最久没查的排前面

老的 `findEnabled(int limit)` 保留（手工接口或调试可能用），但调度器改用新方法。

### 调度器变更
`ShippingTrackingScheduler.runDailyBatch`：

```java
@Scheduled(cron = "${shipping.tracking.cron:0 0 9 * * *}")
public void runDailyBatch() {
    OffsetDateTime threshold = OffsetDateTime.now()
            .minusHours(properties.getMinRequeryHours());
    List<ShippingTrackingBinding> bindings =
            bindingRepository.findBindingsDueForQuery(threshold);
    for (int i = 0; i < bindings.size(); i++) {
        ShippingTrackingBinding binding = bindings.get(i);
        try {
            trackingService.syncBindingForBatch(binding);
        } catch (Exception error) {
            log.warn("Shipping tracking batch failed for binding {}.",
                    binding.id(), error);  // ← System.err.printf 改为 log.warn
        }
        if (i < bindings.size() - 1) {
            sleepBetweenBindings();
        }
    }
}
```

### 配置变更
```properties
# 移除：shipping.tracking.batch-limit=5
shipping.tracking.min-requery-hours=20
```

`ShippingTrackingProperties`：
- 删除 `batchLimit` getter / setter
- 新增 `minRequeryHours` getter / setter（默认 20）

### 测试
- 新增 `ShippingTrackingBindingRepositoryTest`（如果还没有）测：
  1. `last_query_time` 为 null 的 binding 出现在结果首位
  2. `last_query_time` 大于 threshold 的 binding 不出现
  3. 结果按 `last_query_time asc` 排序
- 扩展 `ShippingTrackingMvpTest`（已有）确保删除 `batch-limit` 后调度仍跑通

---

## 跨项约束 / 注意

### Schema 迁移
- `schema.sql` 用 `add column if not exists` 保持启动幂等
- H2 file 模式 `MODE=MySQL` 支持该语法（已验证：现有 `last_departure` 列就是这么加的，commit `d7fbd4c`）

### 配置默认值
- 所有新配置都给 `${prop:default}` 占位，老部署不显式设置也能跑（除了 #1 的密钥 — 那个有意要 fail-fast）

### 不引入新依赖
- AES-GCM 用 JDK 内置 `javax.crypto`
- 不加 Bouncy Castle、不加 Jasypt（避免引入额外配置面）

### 不动 UI
- 三项都是后端 / 数据层改动
- 前端 `shipping-tracking.html` 不需要改动

### 顺手修
- `Scheduler.System.err.printf` → SLF4J `log.warn`（高优 #6 一并）

---

## 风险与留白

1. **AES 密钥轮换**：v1 设计为单密钥；将来若需要轮换，新加 v2 前缀（解密时按前缀分派旧/新 key），本次不实现。
2. **批量无软上限**：去掉 `batch-limit` 后，binding 数突涨（如 200+）会拉长单次调度时间。当前 10–20s 随机延迟 × 200 ≈ 50min，仍在每天 9 点的窗口内可接受；后续如有需要再加"单次最多 N 个"软上限。
3. **重试用的邮件正文**：从 `before_json/after_json` 反序列化重建，所以**不反映**重试期间发生的"再新一波"变更（那些变更走它们各自的 change_log）。
4. **零迁移设计的副作用**：旧明文 SMTP 行在下次"用户在 UI 上重新编辑"前继续是明文。如果用户从来不编辑，长期保持明文。可接受（因为下个版本可以加 admin 接口 force-rotate，本次不做）。

---

## 不在本次范围
- UI 加变更历史 / 失败详情面板（前端工作量大，单独排）
- 多用户隔离（binding → owner_account_id）
- 调度防重叠（ShedLock）
- snapshot 表保留策略
- 鉴权 / 接口权限

这些都是高/中优后续项目，按计划单独处理。
