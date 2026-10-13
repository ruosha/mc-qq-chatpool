package com.mcqqchatpool.push;

import com.mcqqchatpool.McQqChatPoolPlugin;
import com.mcqqchatpool.pool.ChatMessage;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 主动推送（push 模式）：把聊天池里的消息 POST 给 AstrBot 的推送接收端。
 *
 * <p>什么时候用：MC 服务器不在 AstrBot 那台机器上、轮询拉不到插件 HTTP 接口时。
 * 正常情况下轮询就够了，所以这一段默认关闭（{@code push.enabled: false}）。
 *
 * <p>设计要点：
 * <ul>
 *   <li>发布路径只做 {@link #offer}——往有界队列里塞一下就走，绝不阻塞主线程/HTTP 线程；</li>
 *   <li>一个虚拟线程负责逐个发送，失败按 {@code push.max-retries} 重试；仍失败只记警告，
 *       消息本身已经在本池历史里，不会丢；</li>
 *   <li>队列满了丢最旧的并限流记警告，避免接收端挂掉时内存无限增长；</li>
 *   <li>每次发送都重新读配置，所以 {@code /chatpool reload} 后地址/令牌立即生效。</li>
 * </ul>
 */
public final class WebhookPusher {

    /** 队列积压警告的最小间隔（毫秒）。 */
    private static final long DROP_WARN_INTERVAL_MS = 60_000L;

    private final McQqChatPoolPlugin plugin;
    private final LinkedBlockingQueue<ChatMessage> queue;
    private final HttpClient http;

    private final AtomicLong sentCount = new AtomicLong();
    private final AtomicLong failedCount = new AtomicLong();
    private final AtomicLong droppedCount = new AtomicLong();

    private volatile boolean running;
    private volatile Thread worker;
    private volatile long lastDropWarnAt;

    public WebhookPusher(final McQqChatPoolPlugin plugin) {
        this.plugin = plugin;
        this.queue = new LinkedBlockingQueue<>(Math.max(1, plugin.config().pushQueueSize()));
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(500, plugin.config().pushTimeoutMs())))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    // ------------------------------------------------------------- 生命周期

    /** 启动发送线程（幂等）。 */
    public void start() {
        if (running) {
            return;
        }
        running = true;
        worker = Thread.ofVirtual().name("McQqChatPool-Push").start(this::loop);
        if (configured()) {
            plugin.getLogger().info("已开启主动推送：消息会 POST 到 " + plugin.config().pushUrl());
        }
    }

    /** 停止发送线程（幂等，不会等队列排空）。 */
    public void stop() {
        running = false;
        final Thread thread = worker;
        worker = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private boolean configured() {
        final String url = plugin.config().pushUrl();
        return plugin.config().pushEnabled() && url != null && !url.isBlank();
    }

    // ---------------------------------------------------------------- 入队

    /**
     * 把一条消息放进发送队列（非阻塞，绝不抛异常）。
     *
     * <p>来源/池过滤在这里判断：被排除的消息直接不入队，连队列都不占用。
     */
    public void offer(final ChatMessage message) {
        if (message == null || !configured()) {
            return;
        }
        final String source = message.source() == null ? "" : message.source().toLowerCase(Locale.ROOT);
        if (plugin.config().pushExcludeSources().contains(source)) {
            return;
        }
        final Set<String> pools = plugin.config().pushPools();
        if (!pools.isEmpty() && !pools.contains(message.pool())) {
            return;
        }
        if (queue.offer(message)) {
            if (plugin.config().pushLogRequests()) {
                plugin.getLogger().info("推送入队：<" + message.pool() + "> " + message.sender() + ": " + message.content());
            }
            return;
        }
        // 队列满：丢最旧的，把位置让给新消息（新消息更有价值），并限流警告
        queue.poll();
        queue.offer(message);
        droppedCount.incrementAndGet();
        final long now = System.currentTimeMillis();
        if (now - lastDropWarnAt > DROP_WARN_INTERVAL_MS) {
            lastDropWarnAt = now;
            plugin.getLogger().warning("主动推送队列已满（push.queue-size = " + plugin.config().pushQueueSize()
                    + "），正在丢弃最旧的消息；累计丢弃 " + droppedCount.get() + " 条。请检查接收端是否可用。");
        }
    }

    // ---------------------------------------------------------------- 发送

    private void loop() {
        while (running) {
            try {
                final ChatMessage message = queue.poll(1, TimeUnit.SECONDS);
                if (message != null) {
                    deliver(message);
                }
            } catch (final InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (final Throwable throwable) {
                // 单条消息的问题不该拖垮整个推送线程
                plugin.getLogger().log(Level.WARNING, "推送线程出现异常，已忽略并继续", throwable);
            }
        }
    }

    private void deliver(final ChatMessage message) {
        final int maxRetries = Math.max(1, plugin.config().pushMaxRetries());
        final String url = plugin.config().pushUrl();
        for (int attempt = 1; attempt <= maxRetries && running; attempt++) {
            final String error = post(url, message);
            if (error == null) {
                sentCount.incrementAndGet();
                return;
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(Math.max(0L, plugin.config().pushRetryDelayMs()));
                } catch (final InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } else {
                failedCount.incrementAndGet();
                plugin.getLogger().warning("推送失败（已重试 " + maxRetries + " 次）：" + error
                        + " —— 消息仍在本地池历史里，可用轮询兜底。");
            }
        }
    }

    /** @return null 表示成功，否则返回错误描述 */
    private String post(final String url, final ChatMessage message) {
        try {
            final HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(Math.max(500, plugin.config().pushTimeoutMs())))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("User-Agent", "McQqChatPool")
                    .POST(HttpRequest.BodyPublishers.ofString(message.toJson(), StandardCharsets.UTF_8));
            final String token = plugin.config().pushToken();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
            final HttpResponse<String> response = http.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (plugin.config().pushLogRequests()) {
                plugin.getLogger().info("推送结果：HTTP " + response.statusCode()
                        + (response.body() == null || response.body().isBlank() ? "" : " " + truncate(response.body())));
            }
            if (response.statusCode() / 100 == 2) {
                return null;
            }
            return "HTTP " + response.statusCode() + " " + truncate(response.body());
        } catch (final InterruptedException ex) {
            Thread.currentThread().interrupt();
            return "被中断";
        } catch (final Exception ex) {
            return ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }
    }

    private static String truncate(final String text) {
        if (text == null) {
            return "";
        }
        final String flat = text.replace('\n', ' ').trim();
        return flat.length() > 120 ? flat.substring(0, 120) + "…" : flat;
    }

    // ---------------------------------------------------------------- 统计

    public long sentCount() {
        return sentCount.get();
    }

    public long failedCount() {
        return failedCount.get();
    }

    public long droppedCount() {
        return droppedCount.get();
    }

    /** 供启动日志与 {@code /chatpool info} 展示的一行摘要。 */
    public String summary() {
        if (!configured()) {
            return "关闭";
        }
        return "已开启 → " + plugin.config().pushUrl()
                + "（已发 " + sentCount.get() + "，失败 " + failedCount.get() + "，丢弃 " + droppedCount.get() + "）";
    }
}
