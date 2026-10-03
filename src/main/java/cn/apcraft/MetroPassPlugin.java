package cn.apcraft;

import cn.apcraft.command.PassCommand;
import cn.apcraft.config.PluginSettings;
import cn.apcraft.data.PassStore;
import cn.apcraft.economy.EconomyHook;
import cn.apcraft.fare.FareGuard;
import cn.apcraft.pass.PassManager;
import cn.apcraft.text.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MetroPass 主类。
 *
 * <p>提供 Metro 月票 / 学生票：购票后有效期内乘车不花钱。
 * "免费乘车"通过 {@link FareGuard} 的垫付-报销机制实现，
 * 不修改 Metro 本体，也不依赖任何未公开的票价事件。</p>
 */
public final class MetroPassPlugin extends JavaPlugin {

    private PluginSettings settings;
    private Messages messages;
    private EconomyHook economy;
    private PassStore store;
    private PassManager passManager;
    private FareGuard fareGuard;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        settings = new PluginSettings(this);
        messages = new Messages(this);

        economy = new EconomyHook(this);
        economy.refresh();
        if (!economy.available()) {
            getLogger().warning("未找到 Vault 经济服务：购票与报销会在经济插件就绪后自动启用。");
        }

        store = new PassStore(this);
        store.load();

        passManager = new PassManager(this, settings, store, economy, messages);
        fareGuard = new FareGuard(this, settings, passManager, economy, store, messages);

        PassCommand handler = new PassCommand(this, passManager, fareGuard, economy, store, messages);
        PluginCommand command = getCommand("metropass");
        if (command != null) {
            command.setExecutor(handler);
            command.setTabCompleter(handler);
        }

        fareGuard.initialize();
        passManager.startTasks();

        getLogger().info("MetroPass v" + getDescription().getVersion() + " 已启用。");
    }

    @Override
    public void onDisable() {
        if (passManager != null) {
            passManager.stopTasks();
        }
        if (fareGuard != null) {
            fareGuard.shutdown();
        }
        if (store != null) {
            store.saveNow();
        }
    }

    /** 数据存储（命令层统计用）。 */
    public PassStore store() {
        return store;
    }
}
