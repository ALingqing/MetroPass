package cn.apcraft.economy;

import cn.apcraft.MetroPassPlugin;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * Vault 经济封装。
 *
 * <p>购票（withdraw）用于收费；免费乘车守卫用 deposit / withdraw 完成
 * "垫付-报销-回收"三步操作。</p>
 *
 * <p>经济提供者可能晚于本插件注册（热重载、加载顺序），因此提供
 * {@link #refresh()} 做懒查找；各操作在未就绪时直接返回失败。</p>
 */
public final class EconomyHook {

    private final MetroPassPlugin plugin;
    private Economy economy;

    public EconomyHook(MetroPassPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 查找 Vault 经济服务。
     *
     * @return 找到返回 true
     */
    public boolean refresh() {
        RegisteredServiceProvider<Economy> registration =
                plugin.getServer().getServicesManager().getRegistration(Economy.class);
        economy = registration == null ? null : registration.getProvider();
        return economy != null;
    }

    /** 尝试懒加载并返回是否可用。 */
    public boolean available() {
        if (economy == null) {
            refresh();
        }
        return economy != null;
    }

    public double balance(Player player) {
        return available() ? economy.getBalance(player) : 0.0;
    }

    public boolean has(Player player, double amount) {
        return available() && economy.has(player, amount);
    }

    public boolean withdraw(Player player, double amount) {
        if (!available() || amount <= 0.0) {
            return false;
        }
        EconomyResponse response = economy.withdrawPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    public boolean deposit(Player player, double amount) {
        if (!available() || amount <= 0.0) {
            return false;
        }
        EconomyResponse response = economy.depositPlayer(player, amount);
        return response != null && response.transactionSuccess();
    }

    /** 格式化金额（随经济插件的货币格式）。 */
    public String format(double amount) {
        if (available()) {
            String formatted = economy.format(amount);
            if (formatted != null) {
                return formatted;
            }
        }
        return String.format("%.2f", amount);
    }
}
