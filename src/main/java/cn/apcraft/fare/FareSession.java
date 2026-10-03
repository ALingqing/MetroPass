package cn.apcraft.fare;

import java.util.UUID;

/**
 * 单个玩家的乘车对冲会话（仅主线程访问，无需并发控制）。
 *
 * <p>生命周期：右键车站铁轨 / 打开乘车 GUI 时创建（等待上车）→ 上车后进入
 * 乘车状态 → 下车、超时或离线时结束。会话记录：
 * <ul>
 *   <li>{@link #reserved} —— 尚未回收的垫付总额；</li>
 *   <li>{@link #segmentBase}/{@link #segmentToken} —— 当前观察段的扣费前余额与
 *       版本号（防止过期回调结算错误的数据）；</li>
 *   <li>{@link #lastActivityAt} —— 供看门狗清理超时会话。</li>
 * </ul>
 */
final class FareSession {

    final UUID playerId;
    /** 会话代数：会话被替换/结束时递增，回调据此丢弃过期任务。 */
    final int generation;
    final long createdAt;

    long lastActivityAt;
    /** 尚未回收的垫付额。 */
    double reserved;
    /** 当前观察段的基准余额（null = 当前没有进行中的观察段）。 */
    Double segmentBase;
    /** 观察段版本号：每次 beginSegment 递增，回调必须携带相同的值。 */
    long segmentToken;

    FareSession(UUID playerId, int generation, long now) {
        this.playerId = playerId;
        this.generation = generation;
        this.createdAt = now;
        this.lastActivityAt = now;
    }

    void touch(long now) {
        this.lastActivityAt = now;
    }
}
