package cn.apcraft.data;

import cn.apcraft.MetroPassPlugin;
import cn.apcraft.pass.PassType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * 票据数据存储（plugins/MetroPass/passes.yml）。
 *
 * <p>数据结构：</p>
 * <pre>
 * passes:
 *   &lt;uuid&gt;:
 *     monthly: 1790000000   # 到期时间（epoch 秒）
 *     student: 1790000000
 * stats:
 *   tickets-sold: 12
 *   revenue-total: 1200.0
 *   refunded-total: 86.5
 *   refund-count: 43
 * </pre>
 *
 * <p>与 1.x 的差异：旧版本把票据写进 config.yml 的 passes 段，加载时
 * 会自动迁移到 passes.yml 并从 config.yml 移除。</p>
 */
public final class PassStore {

    private final MetroPassPlugin plugin;
    private final File file;
    private YamlConfiguration yaml = new YamlConfiguration();
    private boolean dirty;

    public PassStore(MetroPassPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "passes.yml");
    }

    // ---------- 加载与保存 ----------

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建数据目录: " + plugin.getDataFolder());
        }
        yaml = YamlConfiguration.loadConfiguration(file);
        boolean migrated = migrateLegacyConfig();
        if (migrated) {
            saveNow();
        }
        plugin.getLogger().info("已加载 " + uuids().size() + " 名玩家的票据数据。");
    }

    /** 把 1.x 留在 config.yml 里的 passes 段迁移到 passes.yml。 */
    private boolean migrateLegacyConfig() {
        FileConfiguration legacy = plugin.getConfig();
        ConfigurationSection section = legacy.getConfigurationSection("passes");
        if (section == null) {
            return false;
        }
        int moved = 0;
        for (String uuidKey : section.getKeys(false)) {
            ConfigurationSection playerSection = section.getConfigurationSection(uuidKey);
            if (playerSection == null) {
                continue;
            }
            for (PassType type : PassType.values()) {
                long expiry = playerSection.getLong(type.id(), 0L);
                if (expiry > 0L) {
                    yaml.set("passes." + uuidKey + "." + type.id(), expiry);
                    moved++;
                }
            }
        }
        legacy.set("passes", null);
        plugin.saveConfig();
        if (moved > 0) {
            plugin.getLogger().info("已从 config.yml 迁移 " + moved + " 条票据记录到 passes.yml。");
        }
        dirty = true;
        return true;
    }

    public boolean isDirty() {
        return dirty;
    }

    /** 立即保存（主线程调用；文件很小）。 */
    public void saveNow() {
        try {
            yaml.save(file);
            dirty = false;
        } catch (IOException e) {
            plugin.getLogger().severe("保存 passes.yml 失败: " + e.getMessage());
        }
    }

    // ---------- 票据 ----------

    public long getExpiry(UUID playerId, PassType type) {
        return yaml.getLong("passes." + playerId + "." + type.id(), 0L);
    }

    public void setExpiry(UUID playerId, PassType type, long expiryEpochSeconds) {
        yaml.set("passes." + playerId + "." + type.id(), expiryEpochSeconds);
        dirty = true;
    }

    public boolean removeExpiry(UUID playerId, PassType type) {
        String path = "passes." + playerId + "." + type.id();
        if (!yaml.contains(path)) {
            return false;
        }
        yaml.set(path, null);
        ConfigurationSection playerSection = yaml.getConfigurationSection("passes." + playerId);
        if (playerSection != null && playerSection.getKeys(false).isEmpty()) {
            yaml.set("passes." + playerId, null);
        }
        dirty = true;
        return true;
    }

    /** 所有有记录（含已过期）的玩家。 */
    public Set<UUID> uuids() {
        ConfigurationSection section = yaml.getConfigurationSection("passes");
        if (section == null) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (String key : section.getKeys(false)) {
            try {
                ids.add(UUID.fromString(key));
            } catch (IllegalArgumentException ignored) {
                // 忽略手工编辑产生的非法 key
            }
        }
        return ids;
    }

    // ---------- 统计（用于经济审计） ----------

    public double stat(String key) {
        return yaml.getDouble("stats." + key, 0.0);
    }

    public void addStat(String key, double delta) {
        yaml.set("stats." + key, stat(key) + delta);
        dirty = true;
    }
}
