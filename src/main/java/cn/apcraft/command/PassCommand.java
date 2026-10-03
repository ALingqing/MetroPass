package cn.apcraft.command;

import cn.apcraft.MetroPassPlugin;
import cn.apcraft.data.PassStore;
import cn.apcraft.economy.EconomyHook;
import cn.apcraft.fare.FareGuard;
import cn.apcraft.pass.PassManager;
import cn.apcraft.pass.PassType;
import cn.apcraft.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * /metropass 命令。
 *
 * <ul>
 *   <li>/metropass buy monthly|student —— 购买 / 续费（未过期时顺延）</li>
 *   <li>/metropass status —— 查看剩余时间</li>
 *   <li>/metropass admin give|remove|info|stats|reload —— 管理员工具</li>
 * </ul>
 */
public final class PassCommand implements CommandExecutor, TabCompleter {

    private final MetroPassPlugin plugin;
    private final PassManager passes;
    private final FareGuard fareGuard;
    private final EconomyHook economy;
    private final PassStore store;
    private final Messages messages;

    public PassCommand(MetroPassPlugin plugin,
                       PassManager passes,
                       FareGuard fareGuard,
                       EconomyHook economy,
                       PassStore store,
                       Messages messages) {
        this.plugin = plugin;
        this.passes = passes;
        this.fareGuard = fareGuard;
        this.economy = economy;
        this.store = store;
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "buy":
                handleBuy(sender, args);
                break;
            case "status":
                handleStatus(sender);
                break;
            case "admin":
                handleAdmin(sender, args);
                break;
            default:
                sendHelp(sender);
                break;
        }
        return true;
    }

    // ---------- 玩家命令 ----------

    private void handleBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only");
            return;
        }
        if (!player.hasPermission("metropass.use")) {
            messages.sendPrefixed(player, "no-permission");
            return;
        }
        if (args.length < 2) {
            messages.sendPrefixed(player, "usage");
            return;
        }
        PassType type = PassType.parse(args[1]);
        if (type == null) {
            messages.sendPrefixed(player, "unknown-type");
            return;
        }
        passes.buy(player, type);
    }

    private void handleStatus(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages.sendPrefixed(sender, "player-only");
            return;
        }
        if (!player.hasPermission("metropass.use")) {
            messages.sendPrefixed(player, "no-permission");
            return;
        }
        passes.sendStatus(player);
    }

    private void sendHelp(CommandSender sender) {
        messages.sendPrefixed(sender, "usage");
        if (sender.hasPermission("metropass.admin")) {
            messages.sendPrefixed(sender, "admin-usage");
        }
    }

    // ---------- 管理员命令 ----------

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("metropass.admin")) {
            messages.sendPrefixed(sender, "no-permission");
            return;
        }
        if (args.length < 2) {
            messages.sendPrefixed(sender, "admin-usage");
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "give":
                handleGive(sender, args);
                break;
            case "remove":
                handleRemove(sender, args);
                break;
            case "info":
                handleInfo(sender, args);
                break;
            case "stats":
                handleStats(sender);
                break;
            case "reload":
                handleReload(sender);
                break;
            default:
                messages.sendPrefixed(sender, "admin-usage");
                break;
        }
    }

    private void handleGive(CommandSender sender, String[] args) {
        if (args.length < 4) {
            messages.sendPrefixed(sender, "admin-usage");
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) {
            return;
        }
        PassType type = PassType.parse(args[3]);
        if (type == null) {
            messages.sendPrefixed(sender, "unknown-type");
            return;
        }
        int extraDays = 0;
        if (args.length >= 5) {
            try {
                extraDays = Integer.parseInt(args[4]);
            } catch (NumberFormatException ignored) {
                // 非法天数使用默认有效期
            }
        }
        long until = passes.give(target.getUniqueId(), type, extraDays);
        messages.sendPrefixed(sender, "admin-given",
                "player", nameOf(target),
                "type", type.displayName(),
                "until", PassManager.formatUntil(until));
    }

    private void handleRemove(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.sendPrefixed(sender, "admin-usage");
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) {
            return;
        }
        List<PassType> types = new ArrayList<>();
        if (args.length >= 4) {
            PassType type = PassType.parse(args[3]);
            if (type == null) {
                messages.sendPrefixed(sender, "unknown-type");
                return;
            }
            types.add(type);
        } else {
            types.add(PassType.MONTHLY);
            types.add(PassType.STUDENT);
        }
        for (PassType type : types) {
            if (passes.remove(target.getUniqueId(), type)) {
                messages.sendPrefixed(sender, "admin-removed",
                        "player", nameOf(target),
                        "type", type.displayName());
            } else {
                messages.sendPrefixed(sender, "admin-remove-none",
                        "player", nameOf(target),
                        "type", type.displayName());
            }
        }
    }

    private void handleInfo(CommandSender sender, String[] args) {
        if (args.length < 3) {
            messages.sendPrefixed(sender, "admin-usage");
            return;
        }
        OfflinePlayer target = resolvePlayer(sender, args[2]);
        if (target == null) {
            return;
        }
        String description = passes.describe(target.getUniqueId());
        if (description == null) {
            messages.sendPrefixed(sender, "admin-info-none", "player", nameOf(target));
        } else {
            messages.sendPrefixed(sender, "admin-info-line", "player", nameOf(target), "list", description);
        }
    }

    private void handleStats(CommandSender sender) {
        long sold = (long) store.stat("tickets-sold");
        double revenue = store.stat("revenue-total");
        double refunded = store.stat("refunded-total");
        long count = (long) store.stat("refund-count");
        messages.sendPrefixed(sender, "admin-stats",
                "sold", String.valueOf(sold),
                "revenue", economy.format(revenue),
                "refunded", economy.format(refunded),
                "count", String.valueOf(count));
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadConfig();
        messages.sendPrefixed(sender, "admin-reload");
        fareGuard.initialize();
        if (!fareGuard.isActive() && plugin.getConfig().getBoolean("fare-guard.enabled", true)) {
            messages.sendPrefixed(sender, "fare-guard-inactive");
        }
    }

    // ---------- 工具 ----------

    private OfflinePlayer resolvePlayer(CommandSender sender, String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
        if (offline.hasPlayedBefore()) {
            return offline;
        }
        messages.sendPrefixed(sender, "admin-player-not-found");
        return null;
    }

    private static String nameOf(OfflinePlayer player) {
        String name = player.getName();
        return name == null ? player.getUniqueId().toString() : name;
    }

    // ---------- 补全 ----------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("metropass.use")) {
            return List.of();
        }
        if (args.length == 1) {
            return filter(List.of("buy", "status", "admin"), args[0]);
        }
        if (args[0].equalsIgnoreCase("buy") && args.length == 2) {
            return filter(List.of("monthly", "student"), args[1]);
        }
        if (args[0].equalsIgnoreCase("admin") && sender.hasPermission("metropass.admin")) {
            if (args.length == 2) {
                return filter(List.of("give", "remove", "info", "stats", "reload"), args[1]);
            }
            if (args.length == 3 && matches(args[1], "give", "remove", "info")) {
                return filter(onlineNames(), args[2]);
            }
            if (args.length == 4 && matches(args[1], "give", "remove")) {
                return filter(List.of("monthly", "student"), args[3]);
            }
            if (args.length == 5 && matches(args[1], "give")) {
                return filter(List.of("30"), args[4]);
            }
        }
        return List.of();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            names.add(online.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }

    private static boolean matches(String value, String... options) {
        for (String option : options) {
            if (option.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
    }
}
