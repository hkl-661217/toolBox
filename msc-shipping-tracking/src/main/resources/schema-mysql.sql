create table if not exists shipping_tracking_binding (
    id bigint not null auto_increment,
    order_no varchar(128) not null comment '订单号',
    booking_no varchar(128) not null comment '订舱号',
    carrier varchar(32) not null default 'MSC' comment '船公司，第一版固定 MSC',
    enabled boolean not null default true comment '是否启用',
    last_status varchar(32) comment '最后查询状态',
    last_eta varchar(64) comment '最后 ETA',
    last_node varchar(255) comment '最后节点',
    last_departure varchar(255) comment '最新开船事件，格式 日期|位置|描述，优先 ATD 否则 ETD',
    last_query_time datetime(6) comment '最后查询时间',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '更新时间',
    primary key (id),
    constraint uk_shipping_tracking_binding_order_no unique (order_no),
    constraint uk_shipping_tracking_binding_booking_no unique (booking_no)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='海运轨迹绑定关系表';

create table if not exists shipping_tracking_snapshot (
    id bigint not null auto_increment,
    binding_id bigint not null comment '绑定ID',
    query_time datetime(6) not null comment '查询时间',
    status varchar(32) not null comment '查询状态，SUCCESS / NO_RESULT / FAILED / MANUAL_REQUIRED',
    events_json longtext comment '物流事件前四列 JSON',
    raw_text longtext comment '页面原始可见文本',
    eta varchar(64) comment '预计到港时间',
    latest_node varchar(255) comment '最新节点',
    screenshot_path varchar(1024) comment '截图路径',
    error_reason varchar(2048) comment '失败原因',
    baseline boolean not null default false comment '是否为基线快照',
    created_at datetime(6) not null comment '创建时间',
    primary key (id),
    constraint fk_shipping_tracking_snapshot_binding foreign key (binding_id) references shipping_tracking_binding (id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='海运轨迹查询快照表';

create table if not exists shipping_tracking_change_log (
    id bigint not null auto_increment,
    binding_id bigint not null comment '绑定ID',
    previous_snapshot_id bigint not null comment '上一份快照ID',
    current_snapshot_id bigint not null comment '当前快照ID',
    change_type varchar(64) not null comment '变化类型',
    change_summary varchar(2048) not null comment '变化摘要',
    before_json longtext comment '变更前 JSON',
    after_json longtext comment '变更后 JSON',
    email_sent boolean not null default false comment '是否已发送邮件',
    email_sent_time datetime(6) comment '邮件发送时间',
    retry_count int not null default 0 comment '邮件重发次数（首次失败计为 0，每次补偿 +1）',
    last_retry_at datetime(6) comment '最近一次重发尝试时间（含失败）',
    give_up_at datetime(6) comment '超过重试窗口后由 RetryJob 标记的放弃时间，配合 WARN 日志审计',
    created_at datetime(6) not null comment '创建时间',
    primary key (id),
    constraint fk_shipping_tracking_change_log_binding foreign key (binding_id) references shipping_tracking_binding (id),
    constraint fk_shipping_tracking_change_log_previous_snapshot foreign key (previous_snapshot_id) references shipping_tracking_snapshot (id),
    constraint fk_shipping_tracking_change_log_current_snapshot foreign key (current_snapshot_id) references shipping_tracking_snapshot (id)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='海运轨迹变化记录表';

create index if not exists idx_change_log_pending_retry
    on shipping_tracking_change_log (email_sent, retry_count, created_at);

create table if not exists shipping_tracking_notification_account (
    id bigint not null auto_increment,
    email varchar(200) not null comment '邮箱（同时作为 From 和 To）',
    smtp_password varchar(300) not null comment 'SMTP 授权码（AES-GCM-256 加密，前缀 v1: 后为 base64(iv):base64(ct)）',
    enabled boolean not null default true comment '是否启用',
    created_at datetime(6) not null comment '创建时间',
    updated_at datetime(6) not null comment '更新时间',
    primary key (id),
    constraint uk_shipping_tracking_notification_account_email unique (email)
) engine=innodb default charset=utf8mb4 collate=utf8mb4_0900_ai_ci comment='通知邮箱账号表（邮箱 + SMTP 授权码，自己给自己发）';
