package cn.apcraft.pass;

import cn.apcraft.MetroPassPlugin;
import cn.apcraft.config.PluginSettings;
import cn.apcraft.data.PassStore;
import cn.apcraft.economy.EconomyHook;
import cn.apcraft.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 票据业务：购买 / 续费 / 发放 / 移除 / 查询 / 过期清理。
 *
 * <p>购买规则：未过期时再次购买会叠加时长（从现有到期时间顺延），
 * 而不是拒绝，见 {@link #buy(Player, PassType)}。</p>
 */
public final class PassManager {

    private static final DateTimeFormatter UNTIL_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

    private final MetroPassPlugin plugin;
    private final PluginSettings settings;
    private final PassStore store;
    private final EconomyHook economy;
    private final Messages messages;
    private int taskId = -1;

    public PassManager(MetroPassPlugin plugin,
                       PluginSettings settings,
                       PassStore store,
                       EconomyHook economy,
                       Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.store = store;
        this.economy = economy;
        this.messages = messages;
    }

    // ---------- 查询 ----------

    /** 是否持有任意有效票。 */
    public boolean hasPass(UUID playerId) {
        long now = nowSeconds();
        for (PassType type : PassType.values()) {
            if (store.getExpiry(playerId, type) > now) {
                return true;
            }
        }
        return false;
    }

    public long expiry(UUID playerId, PassType type) {
        return store.getExpiry(playerId, type);
    }

    // ---------- 购买 ----------

    public void buy(Player player, PassType type) {
        if (!economy.available()) {
            messages.sendPrefixed(player, "economy-unavailable");
            return;
        }
        double price = settings.price(type);
        long now = nowSeconds();
        long current = store.getExpiry(player.getUniqueId(), type);

        if (!economy.has(player, price)) {
            messages.sendPrefixed(player, "buy-insufficient",
                    "type", type.displayName(),
                    "price", economy.format(price));
            return;
        }
        if (!economy.withdraw(player, price)) {
            messages.sendPrefixed(player, "buy-failed");
            return;
        }

        long base = Math.max(now, current);
        long newExpiry = base + settings.durationSeconds();
        store.setExpiry(player.getUniqueId(), type, newExpiry);
        store.addStat("tickets-sold", 1);
        store.addStat("revenue-total", price);
        store.saveNow();

        String key = current > now ? "renew-success" : "buy-success";
        messages.sendPrefixed(player, key,
                "type", type.displayName(),
                "price", economy.format(price),
                "until", formatUntil(newExpiry));
    }

    // ---------- 状态 ----------

    public void sendStatus(Player player) {
        long now = nowSeconds();
        boolean any = false;
        for (PassType type : PassType.values()) {
            long expiry = store.getExpiry(player.getUniqueId(), type);
            if (expiry > now) {
                any = true;
                messages.sendPrefixed(player, "status-line",
                        "type", type.displayName(),
                        "remaining", formatRemaining(expiry - now),
                        "until", formatUntil(expiry));
            }
        }
        if (!any) {
            messages.sendPrefixed(player, "status-none");
        }
    }

    // ---------- 管理员操作 ----------

    /**
     * 为玩家添加票据时长。
     *
     * @param extraDays 额外天数；&lt;=0 时使用配置的默认有效期
     * @return 新的到期时间（epoch 秒）
     */
    public long give(UUID playerId, PassType type, int extraDays) {
        long now = nowSeconds();
        long current = store.getExpiry(playerId, type);
        long base = Math.max(now, current);
        long addedDays = extraDays > 0 ? extraDays : settings.durationDays();
        long newExpiry = base + addedDays * 86400L;
        store.setExpiry(playerId, type, newExpiry);
        store.addStat("tickets-given", 1);
        store.saveNow();
        return newExpiry;
    }

    /**
     * 移除某票种。
     *
     * @return 是否有记录被移除
     */
    public boolean remove(UUID playerId, PassType type) {
        boolean removed = store.removeExpiry(playerId, type);
        if (removed) {
            store.saveNow();
        }
        return removed;
    }

    /** 描述玩家当前有效票据；没有则返回 null。 */
    public String describe(UUID playerId) {
        long now = nowSeconds();
        List<String> parts = new ArrayList<>();
        for (PassType type : PassType.values()) {
            long expiry = store.getExpiry(playerId, type);
            if (expiry > now) {
                parts.add(type.displayName() + "（剩余 " + formatRemaining(expiry - now) + "）");
            }
        }
        return parts.isEmpty() ? null : String.join("、", parts);
    }

    // ---------- 定时任务：过期清理 + 数据落盘 ----------

    public void startTasks() {
        long periodTicks = Math.max(200L, settings.saveIntervalSeconds() * 20L);
        taskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::tick, periodTicks, periodTicks)
                .getTaskId();
    }

    public void stopTasks() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    private void tick() {
        cleanupExpired();
        if (store.isDirty()) {
            store.saveNow();
        }
    }

    /** 清理过期超过保留期的记录，避免 passes.yml 无限增长。 */
    private void cleanupExpired() {
        long threshold = nowSeconds() - settings.expiredRetainDays() * 86400L;
        for (UUID playerId : store.uuids()) {
            for (PassType type : PassType.values()) {
                long expiry = store.getExpiry(playerId, type);
                if (expiry > 0L && expiry < threshold) {
                    store.removeExpiry(playerId, type);
                }
            }
        }
    }

    // ---------- 时间格式化 ----------

    public static String formatRemaining(long seconds) {
        if (seconds <= 0L) {
            return "0 秒";
        }
        long days = seconds / 86400L;
        long hours = (seconds % 86400L) / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        if (days > 0L) {
            return days + " 天 " + hours + " 小时";
        }
        if (hours > 0L) {
            return hours + " 小时 " + minutes + " 分";
        }
        if (minutes > 0L) {
            return minutes + " 分钟";
        }
        return seconds + " 秒";
    }

    public static String formatUntil(long epochSeconds) {
        return UNTIL_FORMAT.format(Instant.ofEpochSecond(epochSeconds));
    }

    private static long nowSeconds() {
        return Instant.now().getEpochSecond();
    }
}
