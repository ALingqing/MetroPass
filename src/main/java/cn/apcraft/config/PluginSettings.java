package cn.apcraft.config;

import cn.apcraft.MetroPassPlugin;
import cn.apcraft.pass.PassType;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * config.yml 的读取入口。
 *
 * <p>为避免 /metropass admin reload 之后组件仍持有旧值，这里不缓存任何字段，
 * 每次访问实时读取当前配置（Bukkit 配置读取是内存操作，频率低，无性能问题）。</p>
 */
public final class PluginSettings {

    private final MetroPassPlugin plugin;

    public PluginSettings(MetroPassPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    // ---------- 票价与有效期 ----------

    public double priceMonthly() {
        return Math.max(0.0, cfg().getDouble("prices.monthly", 100.0));
    }

    public double priceStudent() {
        return Math.max(0.0, cfg().getDouble("prices.student", 50.0));
    }

    public double price(PassType type) {
        return type == PassType.STUDENT ? priceStudent() : priceMonthly();
    }

    public int durationDays() {
        return Math.max(1, cfg().getInt("duration-days", 30));
    }

    public long durationSeconds() {
        return durationDays() * 86400L;
    }

    // ---------- 数据维护 ----------

    public int expiredRetainDays() {
        return Math.max(0, cfg().getInt("data.expired-retain-days", 30));
    }

    public int saveIntervalSeconds() {
        return Math.max(10, cfg().getInt("data.save-interval-seconds", 60));
    }

    // ---------- 免费乘车（FareGuard）参数 ----------

    public boolean fareGuardEnabled() {
        return cfg().getBoolean("fare-guard.enabled", true);
    }

    /** 单个观察窗口允许报销的最大金额，超过视为异常（不发放并告警）。 */
    public double maxRefundPerSegment() {
        return Math.max(0.0, cfg().getDouble("fare-guard.max-refund-per-segment", 10000.0));
    }

    /** 单次垫付上限，防止估算异常时大额资金进入玩家账户。 */
    public double maxReserve() {
        return Math.max(0.0, cfg().getDouble("fare-guard.max-reserve", 10000.0));
    }

    /** 垫付缓冲：目标余额 = 最低票价估算 + buffer。 */
    public double reserveBuffer() {
        return Math.max(0.0, cfg().getDouble("fare-guard.reserve-buffer", 1.0));
    }

    /** 会话超时（秒）：右键触发后迟迟未完成乘车时，在此时间后回收垫付。 */
    public long sessionTimeoutSeconds() {
        return Math.max(5L, cfg().getLong("fare-guard.session-timeout-seconds", 15L));
    }

    public long sessionTimeoutMillis() {
        return sessionTimeoutSeconds() * 1000L;
    }

    /** 报销完成后是否给玩家发送提示。 */
    public boolean notifyRefund() {
        return cfg().getBoolean("fare-guard.notify-refund", true);
    }

    public boolean debug() {
        return cfg().getBoolean("fare-guard.debug", false);
    }
}
