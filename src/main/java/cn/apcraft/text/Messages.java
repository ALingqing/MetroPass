package cn.apcraft.text;

import cn.apcraft.MetroPassPlugin;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;

import java.util.Map;

/**
 * 消息读取与格式化。
 *
 * <p>消息文本全部来自 config.yml 的 messages 段（默认中文）；
 * 若用户删除了某个键，则退回内置默认文本，保证插件始终能与人对话。</p>
 *
 * <p>支持 &amp; 颜色代码与 {占位符} 替换。</p>
 */
public final class Messages {

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry("prefix", "&8[&b月票&8] &7"),
            Map.entry("no-permission", "&c你没有权限这样做。"),
            Map.entry("player-only", "&c该命令只能由玩家执行。"),
            Map.entry("usage", "&7使用：&f/metropass buy monthly|student&7，&f/metropass status"),
            Map.entry("admin-usage", "&7/metropass admin give|remove|info|stats|reload"),
            Map.entry("unknown-type", "&c票种必须是 monthly 或 student。"),
            Map.entry("economy-unavailable", "&c服务器经济不可用（未找到 Vault 经济服务）。"),
            Map.entry("buy-insufficient", "&c余额不足：{type} 需要 {price}。"),
            Map.entry("buy-failed", "&c购买失败：扣款未成功，请稍后再试。"),
            Map.entry("buy-success", "&a已购买{type}：花费 {price}，有效期至 {until}。"),
            Map.entry("renew-success", "&a已续费{type}：花费 {price}，新的有效期至 {until}。"),
            Map.entry("status-none", "&e你当前没有有效的月票。"),
            Map.entry("status-line", "&a有效{type}：剩余 {remaining}（至 {until}）。"),
            Map.entry("refund-notify", "&b月票已覆盖本次车费 &f{price}&b，已原路退还。"),
            Map.entry("admin-given", "&a已为 {player} 添加{type}，有效期至 {until}。"),
            Map.entry("admin-removed", "&a已移除 {player} 的{type}。"),
            Map.entry("admin-remove-none", "&e{player} 没有{type}记录。"),
            Map.entry("admin-player-not-found", "&c找不到该玩家（需要其曾进入过服务器）。"),
            Map.entry("admin-info-line", "&7{player}：{list}"),
            Map.entry("admin-info-none", "&7{player}：没有任何月票记录。"),
            Map.entry("admin-stats", "&7累计售票 {sold} 张（票款 {revenue}）；累计报销 {refunded}（{count} 次）。"),
            Map.entry("admin-reload", "&a配置与消息已重新加载。"),
            Map.entry("fare-guard-inactive", "&e提示：免费乘车守卫未启用（详见控制台日志），月票目前不会退返车费。")
    );

    private final MetroPassPlugin plugin;

    public Messages(MetroPassPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 读取并格式化消息。
     *
     * @param key          消息键（不含 messages. 前缀）
     * @param placeholders 成对的 占位符名, 值（如 "price", "$5"）
     */
    public String format(String key, String... placeholders) {
        String raw = plugin.getConfig().getString("messages." + key);
        if (raw == null) {
            raw = DEFAULTS.getOrDefault(key, "&c缺少消息: " + key);
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            raw = raw.replace("{" + placeholders[i] + "}", placeholders[i + 1]);
        }
        return ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** 发送不带前缀的消息。 */
    public void send(CommandSender target, String key, String... placeholders) {
        target.sendMessage(format(key, placeholders));
    }

    /** 发送带前缀的消息。 */
    public void sendPrefixed(CommandSender target, String key, String... placeholders) {
        target.sendMessage(format("prefix") + format(key, placeholders));
    }
}
