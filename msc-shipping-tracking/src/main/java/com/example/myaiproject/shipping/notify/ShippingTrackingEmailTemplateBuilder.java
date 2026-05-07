package com.example.myaiproject.shipping.notify;

import com.example.myaiproject.shipping.model.ShippingTrackingBinding;
import com.example.myaiproject.shipping.model.ShippingTrackingEvent;
import com.example.myaiproject.shipping.model.ShippingTrackingEventChange;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ShippingTrackingEmailTemplateBuilder {
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss xxx");

    public EmailContent buildChangeNotification(
            ShippingTrackingBinding binding,
            String latestEta,
            String latestNode,
            List<ShippingTrackingEventChange> changes,
            OffsetDateTime now) {
        List<ShippingTrackingEventChange> safeChanges = changes == null ? List.of() : changes;
        String subject = "MSC货物追踪变更通知｜订单号 "
                + safe(binding.orderNo())
                + "｜"
                + safeChanges.size()
                + " 条变化";
        return new EmailContent(
                subject,
                buildChangeNotificationHtml(binding, latestEta, latestNode, safeChanges, now));
    }

    public EmailContent buildTestEmail() {
        return new EmailContent(
                "MSC物流提醒测试邮件",
                """
                <div style="max-width:760px;margin:0 auto;padding:24px;background:#f3f5f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;color:#172033;">
                  <div style="background:#ffffff;border-radius:12px;padding:24px;border:1px solid #e2e8f0;">
                    <h1 style="margin:0 0 12px;font-size:22px;line-height:1.35;color:#12345b;">MSC物流提醒测试邮件</h1>
                    <p style="margin:0;font-size:14px;line-height:1.8;">如果你收到这封邮件，说明 QQ 邮箱 SMTP 配置成功。</p>
                  </div>
                </div>
                """);
    }

    private String buildChangeNotificationHtml(
            ShippingTrackingBinding binding,
            String latestEta,
            String latestNode,
            List<ShippingTrackingEventChange> changes,
            OffsetDateTime now) {
        String currentTime = DATE_TIME_FORMATTER.format(now);
        String firstConclusion = oneLineConclusion(binding, changes);
        StringBuilder html = new StringBuilder();
        html.append("<div style=\"margin:0;padding:24px;background:#f3f5f8;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI','Microsoft YaHei',sans-serif;color:#172033;\">");
        html.append("<div style=\"max-width:760px;margin:0 auto;\">");
        appendHeader(html, currentTime);
        appendLead(html, binding, changes.size());
        appendOneLine(html, firstConclusion);
        appendOverview(html, binding, latestEta, latestNode, changes.size(), currentTime);
        appendChangeTable(html, changes);
        appendTextDetails(html, changes);
        appendSuggestions(html);
        html.append("</div></div>");
        return html.toString();
    }

    private static void appendHeader(StringBuilder html, String currentTime) {
        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:24px 26px;margin-bottom:14px;\">");
        html.append("<p style=\"margin:0 0 8px;color:#b78b28;font-size:13px;font-weight:700;\">货物追踪看板</p>");
        html.append("<h1 style=\"margin:0;color:#12345b;font-size:26px;line-height:1.3;font-weight:800;\">【MSC货物追踪变更通知】")
                .append(escape(currentTime))
                .append("</h1>");
        html.append("</div>");
    }

    private static void appendLead(StringBuilder html, ShippingTrackingBinding binding, int changeCount) {
        html.append("<div style=\"background:#fff7df;border:1px solid #f0d48a;border-left:6px solid #b78b28;border-radius:12px;padding:18px 20px;margin-bottom:14px;\">");
        html.append("<h2 style=\"margin:0 0 8px;color:#7a5a13;font-size:18px;line-height:1.4;\">结论先行</h2>");
        html.append("<p style=\"margin:0;font-size:14px;line-height:1.8;\">订单 <strong>")
                .append(escape(binding.orderNo()))
                .append("</strong> 绑定的 MSC 订舱号货物追踪信息发生变化。本次共检测到 <strong>")
                .append(changeCount)
                .append("</strong> 条变化，请关注预计开船、预计到港或最新动向是否影响后续交付安排。</p>");
        html.append("</div>");
    }

    private static void appendOneLine(StringBuilder html, String firstConclusion) {
        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:18px 20px;margin-bottom:14px;\">");
        html.append("<h2 style=\"margin:0 0 8px;color:#12345b;font-size:18px;line-height:1.4;\">本次一句话结论</h2>");
        html.append("<p style=\"margin:0;font-size:14px;line-height:1.8;\">")
                .append(escape(firstConclusion))
                .append("</p>");
        html.append("</div>");
    }

    private static void appendOverview(
            StringBuilder html,
            ShippingTrackingBinding binding,
            String latestEta,
            String latestNode,
            int changeCount,
            String currentTime) {
        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:18px 20px;margin-bottom:14px;\">");
        html.append("<h2 style=\"margin:0 0 14px;color:#12345b;font-size:18px;line-height:1.4;\">当前概览</h2>");
        html.append("<table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;\">");
        appendOverviewRow(html, "订单号", binding.orderNo(), "订舱号", binding.bookingNo());
        appendOverviewRow(html, "变化数量", changeCount + " 条", "最新动向", dash(latestNode));
        appendOverviewRow(html, "最新 ETA", dash(latestEta), "检测时间", currentTime);
        html.append("</table>");
        html.append("</div>");
    }

    private static void appendOverviewRow(
            StringBuilder html,
            String leftLabel,
            String leftValue,
            String rightLabel,
            String rightValue) {
        html.append("<tr>");
        appendOverviewCell(html, leftLabel, leftValue);
        appendOverviewCell(html, rightLabel, rightValue);
        html.append("</tr>");
    }

    private static void appendOverviewCell(StringBuilder html, String label, String value) {
        html.append("<td style=\"width:50%;padding:8px;border:1px solid #e2e8f0;background:#f8fafc;vertical-align:top;\">");
        if (label.isBlank()) {
            html.append("&nbsp;");
        } else {
            html.append("<div style=\"color:#64748b;font-size:12px;font-weight:700;line-height:1.5;\">")
                    .append(escape(label))
                    .append("</div>");
            html.append("<div style=\"color:#172033;font-size:14px;font-weight:700;line-height:1.7;\">")
                    .append(escape(value))
                    .append("</div>");
        }
        html.append("</td>");
    }

    private static void appendChangeTable(StringBuilder html, List<ShippingTrackingEventChange> changes) {
        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:18px 20px;margin-bottom:14px;\">");
        html.append("<h2 style=\"margin:0 0 14px;color:#12345b;font-size:18px;line-height:1.4;\">变化明细</h2>");
        html.append("<table cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;border-collapse:collapse;font-size:13px;line-height:1.5;\">");
        html.append("<thead><tr>");
        for (String header : List.of("序号", "变化类型", "日期变更前", "日期变更后", "位置", "描述", "空载/满载/船舶/航次")) {
            html.append("<th style=\"padding:10px 8px;border:1px solid #dbe3ed;background:#f1f5f9;color:#334155;text-align:left;font-weight:800;\">")
                    .append(escape(header))
                    .append("</th>");
        }
        html.append("</tr></thead><tbody>");
        for (ShippingTrackingEventChange change : changes) {
            ShippingTrackingEvent before = change.beforeEvent();
            ShippingTrackingEvent after = change.afterEvent();
            ShippingTrackingEvent event = after == null ? before : after;
            html.append("<tr>");
            appendTableCell(html, String.valueOf(change.number()), false);
            appendTableCell(html, displayChangeType(change), false);
            appendTableCell(html, value(before, ShippingTrackingEvent::date), true);
            appendTableCell(html, value(after, ShippingTrackingEvent::date), true);
            appendTableCell(html, value(event, ShippingTrackingEvent::location), false);
            appendTableCell(html, value(event, ShippingTrackingEvent::description), false);
            appendTableCell(html, value(event, ShippingTrackingEvent::vesselVoyage), false);
            html.append("</tr>");
        }
        html.append("</tbody></table>");
        html.append("</div>");
    }

    private static void appendTableCell(StringBuilder html, String value, boolean strong) {
        html.append("<td style=\"padding:10px 8px;border:1px solid #e2e8f0;color:#172033;vertical-align:top;\">");
        if (strong) {
            html.append("<strong>").append(escape(dash(value))).append("</strong>");
        } else {
            html.append(escape(dash(value)));
        }
        html.append("</td>");
    }

    private static void appendTextDetails(StringBuilder html, List<ShippingTrackingEventChange> changes) {
        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:18px 20px;margin-bottom:14px;\">");
        html.append("<h2 style=\"margin:0 0 14px;color:#12345b;font-size:18px;line-height:1.4;\">变更详情</h2>");
        for (ShippingTrackingEventChange change : changes) {
            html.append("<div style=\"margin:0 0 16px;padding:14px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:10px;\">");
            html.append("<h3 style=\"margin:0 0 10px;color:#12345b;font-size:15px;line-height:1.5;\">【变化 ")
                    .append(change.number())
                    .append("｜")
                    .append(escape(displayChangeType(change)))
                    .append("】</h3>");
            appendEventBlock(html, "变更前", change.beforeEvent());
            appendEventBlock(html, "变更后", change.afterEvent());
            html.append("</div>");
        }
        html.append("</div>");
    }

    private static void appendEventBlock(StringBuilder html, String title, ShippingTrackingEvent event) {
        html.append("<p style=\"margin:10px 0 6px;color:#172033;font-weight:800;font-size:14px;\">")
                .append(escape(title))
                .append("：</p>");
        html.append("<ul style=\"margin:0 0 0 18px;padding:0;color:#334155;font-size:13px;line-height:1.8;\">");
        html.append("<li>日期：").append(escape(value(event, ShippingTrackingEvent::date))).append("</li>");
        html.append("<li>位置：").append(escape(value(event, ShippingTrackingEvent::location))).append("</li>");
        html.append("<li>描述：").append(escape(value(event, ShippingTrackingEvent::description))).append("</li>");
        html.append("<li>空载/满载/船舶/航次：").append(escape(value(event, ShippingTrackingEvent::vesselVoyage))).append("</li>");
        html.append("</ul>");
    }

    private static void appendSuggestions(StringBuilder html) {
        html.append("<div style=\"background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;padding:18px 20px;\">");
        html.append("<h2 style=\"margin:0 0 10px;color:#12345b;font-size:18px;line-height:1.4;\">后续建议</h2>");
        html.append("<ul style=\"margin:0 0 14px 18px;padding:0;color:#334155;font-size:13px;line-height:1.8;\">");
        html.append("<li>预计开船时间发生变化，建议及时确认是否影响后续到港、提柜或交付安排，并视情况同步客户。</li>");
        html.append("<li>如果变化涉及 Estimated Time of Arrival，请关注到港、提柜和后续交付安排。</li>");
        html.append("<li>如果是新增普通节点，通常可作为货物追踪进度更新记录。</li>");
        html.append("</ul>");
        html.append("<p style=\"margin:0;color:#64748b;font-size:12px;line-height:1.7;\">说明：本邮件仅在系统检测到 MSC 货物追踪信息变化时发送；首次建立追踪基线不会发送提醒。</p>");
        html.append("</div>");
    }

    private static String oneLineConclusion(ShippingTrackingBinding binding, List<ShippingTrackingEventChange> changes) {
        if (changes.isEmpty()) {
            return "本次未检测到具体货物追踪变化。";
        }
        ShippingTrackingEventChange change = changes.get(0);
        ShippingTrackingEvent before = change.beforeEvent();
        ShippingTrackingEvent after = change.afterEvent();
        ShippingTrackingEvent event = after == null ? before : after;
        String type = displayChangeType(change);
        if ("预计开船时间变更".equals(type)) {
            return "订单 "
                    + dash(binding.orderNo())
                    + " 的预计开船时间从 "
                    + dash(value(before, ShippingTrackingEvent::date))
                    + " 调整为 "
                    + dash(value(after, ShippingTrackingEvent::date))
                    + "，位置 "
                    + dash(value(event, ShippingTrackingEvent::location))
                    + "，船舶/航次 "
                    + dash(value(event, ShippingTrackingEvent::vesselVoyage))
                    + "。请关注是否需要同步客户交付预期。";
        }
        if ("预计到港时间变更".equals(type)) {
            return "预计到港时间从 "
                    + dash(value(before, ShippingTrackingEvent::date))
                    + " 调整为 "
                    + dash(value(after, ShippingTrackingEvent::date))
                    + "，位置 "
                    + dash(value(event, ShippingTrackingEvent::location))
                    + "，船舶/航次 "
                    + dash(value(event, ShippingTrackingEvent::vesselVoyage))
                    + "。";
        }
        if ("新增物流节点".equals(type)) {
            return "新增一条货物追踪节点："
                    + dash(value(after, ShippingTrackingEvent::date))
                    + "，"
                    + dash(value(after, ShippingTrackingEvent::location))
                    + "，"
                    + dash(value(after, ShippingTrackingEvent::description))
                    + "，"
                    + dash(value(after, ShippingTrackingEvent::vesselVoyage))
                    + "。";
        }
        if ("物流节点移除".equals(type)) {
            return "物流节点移除："
                    + dash(value(before, ShippingTrackingEvent::date))
                    + "，"
                    + dash(value(before, ShippingTrackingEvent::location))
                    + "，"
                    + dash(value(before, ShippingTrackingEvent::description))
                    + "。";
        }
        return "货物追踪节点信息发生变化，位置 "
                + dash(value(event, ShippingTrackingEvent::location))
                + "，描述 "
                + dash(value(event, ShippingTrackingEvent::description))
                + "。";
    }

    private static String displayChangeType(ShippingTrackingEventChange change) {
        ShippingTrackingEvent before = change.beforeEvent();
        ShippingTrackingEvent after = change.afterEvent();
        if (before == null && after != null) {
            return "新增物流节点";
        }
        if (before != null && after == null) {
            return "物流节点移除";
        }
        String description = value(after == null ? before : after, ShippingTrackingEvent::description);
        if ("ETD_CHANGED".equals(change.changeType())
                || ("日期变化".equals(change.changeType()) && description.contains("Estimated Time of Departure"))) {
            return "预计开船时间变更";
        }
        if ("日期变化".equals(change.changeType()) && description.contains("Estimated Time of Arrival")) {
            return "预计到港时间变更";
        }
        return "物流节点信息变更";
    }

    private static String value(ShippingTrackingEvent event, EventValueExtractor extractor) {
        if (event == null) {
            return "";
        }
        return safe(extractor.extract(event));
    }

    private static String dash(String value) {
        return safe(value).isBlank() ? "-" : safe(value);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String escape(String value) {
        return safe(value)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#039;");
    }

    private interface EventValueExtractor {
        String extract(ShippingTrackingEvent event);
    }

    public record EmailContent(String subject, String htmlBody) {
    }
}
