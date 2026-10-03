package cn.apcraft.pass;

import java.util.Locale;

/**
 * 票种定义。
 *
 * <p>月票与学生票都提供 30 天（可通过 config.yml 的 duration-days 配置）免费乘车，
 * 区别只在售价（prices.monthly / prices.student）。</p>
 */
public enum PassType {
    /** 月票 */
    MONTHLY("monthly", "月票"),
    /** 学生票 */
    STUDENT("student", "学生票");

    private final String id;
    private final String displayName;

    PassType(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }

    /** 配置文件 / 命令中使用的 id（monthly / student）。 */
    public String id() {
        return id;
    }

    /** 展示名称（用于消息）。 */
    public String displayName() {
        return displayName;
    }

    /**
     * 解析票种，忽略大小写；无法识别时返回 null。
     */
    public static PassType parse(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT).trim();
        for (PassType type : values()) {
            if (type.id.equals(normalized)) {
                return type;
            }
        }
        return null;
    }
}
