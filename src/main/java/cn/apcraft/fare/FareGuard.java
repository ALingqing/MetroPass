package cn.apcraft.fare;

import cn.apcraft.MetroPassPlugin;
import cn.apcraft.config.PluginSettings;
import cn.apcraft.data.PassStore;
import cn.apcraft.economy.EconomyHook;
import cn.apcraft.pass.PassManager;
import cn.apcraft.text.Messages;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.persistence.PersistentDataType;
import org.cubexmc.metro.api.MetroAPI;
import org.cubexmc.metro.event.MetroTrainArrivalEvent;
import org.cubexmc.metro.gui.GuiHolder;
import org.cubexmc.metro.gui.GuiSlots;
import org.cubexmc.metro.model.Line;
import org.cubexmc.metro.model.LineStatus;
import org.cubexmc.metro.model.PriceRule;
import org.cubexmc.metro.model.Stop;
import org.cubexmc.metro.util.MetroConstants;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 免费乘车守卫 —— 在**不修改 Metro 本体**的前提下，让持票玩家乘车不花钱。
 *
 * <h2>为什么需要它</h2>
 * Metro 1.1.9 没有票价事件/豁免 API，附属插件无法阻止 {@code TicketService} 扣款，
 * 唯一能插手的是扣费发生前的 {@link VehicleEnterEvent}（但只能阻止上车）。
 * 因此这里采用"账户对冲"方案：不阻止扣费，而是替玩家把扣掉的钱补回来。
 *
 * <h2>三步走</h2>
 * <ol>
 *   <li><b>垫付</b>：乘车前（右键车站铁轨 / 在乘车 GUI 点击线路时，均早于
 *       Metro 的余额检查）把余额补足到"该站最低票价估算 + 缓冲"，避免
 *       Metro 因余额不足拒绝上车或扣款失败。</li>
 *   <li><b>报销</b>：在扣费完成后比对观察窗口内的余额差量，把 Metro 扣走的
 *       金额原额退回玩家。观察窗口由四种事件界定：
 *       <ul>
 *         <li>上车：{@link VehicleEnterEvent}（扣费前）→ 1 tick 后结算；</li>
 *         <li>到站：{@code MetroTrainArrivalEvent.ENTERING}（扣费前）→
 *             {@code DOCKED}（扣费后）结算；</li>
 *         <li>中途下车：{@link VehicleExitEvent}（扣费前）→ 2 tick 后结算；</li>
 *         <li>下线：立即结算。</li>
 *       </ul>
 *   </li>
 *   <li><b>回收</b>：垫付完成使命后（上车结算完成）立即收回，只保留玩家自有余额；
 *       会话结束（下车 / 超时 / 离线）时兜底回收。</li>
 * </ol>
 *
 * <h2>兼容性</h2>
 * 依赖 Metro 1.1.9 的运行时时序（支持至 1.1.6 的既有事件均未使用）。
 * 升级 Metro 后若时序变化，最坏情况是"不报销"（玩家正常付费），不会多扣玩家一分钱；
 * 所有金额操作都有上限保护并可整体关闭（config 的 fare-guard.enabled）。
 *
 * <p>全部逻辑在主线程执行（Bukkit 事件与调度器），数据结构为普通 HashMap。</p>
 */
public final class FareGuard implements Listener {

    private static final long WATCHDOG_PERIOD_TICKS = 100L;
    private static final long RETRY_DELAY_TICKS = 100L;
    private static final int MAX_RETRIES = 12;
    /** 乘车中垫付滞留多久后强制尝试回收（毫秒）。 */
    private static final long RIDING_RESERVE_STALE_MS = 60_000L;
    /** 异常长会话兜底清理（毫秒）。 */
    private static final long RIDING_SESSION_MAX_MS = 30 * 60_000L;

    private final MetroPassPlugin plugin;
    private final PluginSettings settings;
    private final PassManager passes;
    private final EconomyHook economy;
    private final PassStore store;
    private final Messages messages;

    private final Map<UUID, FareSession> sessions = new HashMap<>();
    private final Map<UUID, Integer> generations = new HashMap<>();

    private MetroAPI api;
    private boolean active;
    private int watchdogTaskId = -1;
    private int retryTaskId = -1;
    private int retriesLeft = MAX_RETRIES;

    public FareGuard(MetroPassPlugin plugin,
                     PluginSettings settings,
                     PassManager passes,
                     EconomyHook economy,
                     PassStore store,
                     Messages messages) {
        this.plugin = plugin;
        this.settings = settings;
        this.passes = passes;
        this.economy = economy;
        this.store = store;
        this.messages = messages;
    }

    // ==================== 生命周期 ====================

    /**
     * 尝试启用。环境未就绪（经济服务/MetroAPI 晚到）时会自动稍后重试；
     * 版本不兼容（缺少所需类）时直接放弃并告警。
     */
    public void initialize() {
        if (active) {
            return;
        }
        if (!settings.fareGuardEnabled()) {
            plugin.getLogger().info("FareGuard 已在配置中关闭（玩家照常付费乘车）。");
            return;
        }
        if (!economy.available()) {
            scheduleRetry("Vault 经济服务尚未就绪");
            return;
        }
        if (!probeClasses()) {
            return;
        }
        api = MetroAPI.getInstance();
        if (api == null) {
            scheduleRetry("MetroAPI 尚未初始化");
            return;
        }
        active = true;
        if (retryTaskId != -1) {
            Bukkit.getScheduler().cancelTask(retryTaskId);
            retryTaskId = -1;
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        watchdogTaskId = Bukkit.getScheduler()
                .runTaskTimer(plugin, this::watchdog, WATCHDOG_PERIOD_TICKS, WATCHDOG_PERIOD_TICKS)
                .getTaskId();
        plugin.getLogger().info("FareGuard 已启用：持票玩家乘车产生的车费将通过垫付-报销方式全额返还。");
    }

    private void scheduleRetry(String reason) {
        if (retriesLeft-- <= 0) {
            plugin.getLogger().warning("FareGuard 启用失败，已放弃重试：" + reason);
            return;
        }
        if (retryTaskId != -1) {
            return;
        }
        plugin.getLogger().info("FareGuard 等待环境就绪（" + reason + "），稍后重试…");
        retryTaskId = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            retryTaskId = -1;
            initialize();
        }, RETRY_DELAY_TICKS).getTaskId();
    }

    /** 确认当前 Metro 版本包含所需类，避免 Event 注册时抛 NoClassDefFoundError。 */
    private boolean probeClasses() {
        String[] required = {
                "org.cubexmc.metro.api.MetroAPI",
                "org.cubexmc.metro.gui.GuiHolder",
                "org.cubexmc.metro.gui.GuiSlots",
                "org.cubexmc.metro.event.MetroTrainArrivalEvent",
                "org.cubexmc.metro.event.TrainEnterStopEvent",
                "org.cubexmc.metro.util.MetroConstants",
                "org.cubexmc.metro.model.PriceRule",
        };
        try {
            for (String name : required) {
                Class.forName(name);
            }
            return true;
        } catch (Throwable t) {
            plugin.getLogger().warning("FareGuard 未启用：当前 Metro 版本缺少所需类（" + t.getMessage()
                    + "）。请搭配 Metro 1.1.9 使用，或在配置中关闭 fare-guard。");
            return false;
        }
    }

    public boolean isActive() {
        return active;
    }

    /** 关闭：取消任务并尽最大努力回收所有垫付。 */
    public void shutdown() {
        active = false;
        if (retryTaskId != -1) {
            Bukkit.getScheduler().cancelTask(retryTaskId);
            retryTaskId = -1;
        }
        if (watchdogTaskId != -1) {
            Bukkit.getScheduler().cancelTask(watchdogTaskId);
            watchdogTaskId = -1;
        }
        for (FareSession session : new ArrayList<>(sessions.values())) {
            try {
                Player player = Bukkit.getPlayer(session.playerId);
                if (player != null && player.isOnline()) {
                    recycleReserve(player, session, true);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("关闭时回收垫付异常: " + t.getMessage());
            }
        }
        sessions.clear();
        generations.clear();
    }

    // ==================== 事件：垫付 ====================

    /** 右键车站铁轨：单线路直达上车（Metro 会立刻做余额检查），这里提前垫付。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!active || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !block.getType().name().contains("RAIL")) {
            return;
        }
        Player player = event.getPlayer();
        if (!passes.hasPass(player.getUniqueId()) || isRidingMetroMinecart(player)) {
            return;
        }
        try {
            Stop stop = api.getStopManager()
                    .getBestStopContainingLocation(block.getLocation(), player.getLocation().getYaw());
            if (stop != null) {
                ensureReserve(player, stop);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("垫付逻辑异常（interact）: " + t);
        }
    }

    /** 在乘车线路选择 GUI 点击线路：多线路场景的兜底垫付（同样早于 Metro 的余额检查）。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onGuiClick(InventoryClickEvent event) {
        if (!active || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getView().getTopInventory().getHolder();
        if (!(holder instanceof GuiHolder gui)
                || gui.getType() != GuiHolder.GuiType.LINE_BOARDING_CHOICE) {
            return;
        }
        if (!passes.hasPass(player.getUniqueId()) || isRidingMetroMinecart(player)) {
            return;
        }
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= GuiSlots.ITEMS_PER_PAGE) {
            return;
        }
        List<String> lineIds = gui.getData("lineIds");
        if (lineIds == null || rawSlot >= lineIds.size()) {
            return;
        }
        String stopId = gui.getData("stopId");
        if (stopId == null) {
            return;
        }
        try {
            Stop stop = api.getStop(stopId);
            if (stop != null) {
                ensureReserve(player, stop);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("垫付逻辑异常（gui）: " + t);
        }
    }

    // ==================== 事件：观察段与结算 ====================

    /** 上车（Metro 的 addPassenger 流程内、扣费之前）：记录段基准并预约结算。 */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (!active || event.isCancelled()) {
            return;
        }
        if (!(event.getVehicle() instanceof Minecart cart) || !isMetroMinecart(cart)) {
            return;
        }
        if (!(event.getEntered() instanceof Player player)) {
            return;
        }
        if (!passes.hasPass(player.getUniqueId())) {
            return;
        }
        try {
            FareSession session = session(player, true);
            beginSegment(player, session);
            scheduleSettle(player, session, session.segmentToken, 1L, false);
        } catch (Throwable t) {
            plugin.getLogger().warning("上车结算预约异常: " + t);
        }
    }

    /**
     * 到站事件：ENTERING 记录扣费前基准；DOCKED 时 Metro 已在同一 tick 内完成
     * 逐段扣费，直接结算。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTrainArrival(MetroTrainArrivalEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getPassenger();
        if (player == null || !player.isOnline() || !passes.hasPass(player.getUniqueId())) {
            return;
        }
        FareSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        try {
            if (event.getArrivalType() == MetroTrainArrivalEvent.ArrivalType.ENTERING) {
                beginSegment(player, session);
            } else if (event.getArrivalType() == MetroTrainArrivalEvent.ArrivalType.DOCKED) {
                settleNow(player, session, false);
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("到站结算异常: " + t);
        }
    }

    /** 下车（Metro 在其后立即结算中途下车费）：记录基准，稍后结算并结束会话。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onVehicleExit(VehicleExitEvent event) {
        if (!active) {
            return;
        }
        if (!(event.getVehicle() instanceof Minecart cart) || !isMetroMinecart(cart)) {
            return;
        }
        if (!(event.getExited() instanceof Player player)) {
            return;
        }
        if (!passes.hasPass(player.getUniqueId())) {
            return;
        }
        FareSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        try {
            beginSegment(player, session);
            scheduleSettle(player, session, session.segmentToken, 2L, true);
        } catch (Throwable t) {
            plugin.getLogger().warning("下车结算预约异常: " + t);
        }
    }

    /** 玩家离线：结算未完成的段并回收垫付。 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (!active) {
            return;
        }
        Player player = event.getPlayer();
        FareSession session = sessions.get(player.getUniqueId());
        if (session == null) {
            return;
        }
        try {
            settleNow(player, session, false);
            finishRide(player, session);
        } catch (Throwable t) {
            plugin.getLogger().warning("离线结算异常: " + t);
            sessions.remove(player.getUniqueId());
        }
    }

    // ==================== 垫付 ====================

    /**
     * 确保玩家余额足以通过 Metro 的余额检查与扣款。
     * 目标 = 该站所有可乘线路的"最低票价估算"最大值 + 缓冲。
     */
    private void ensureReserve(Player player, Stop stop) {
        double estimate = estimateMax(stop);
        if (estimate <= 0.0) {
            return;
        }
        double target = estimate + settings.reserveBuffer();
        double balance = economy.balance(player);
        double topUp = FareMath.topUp(balance, target, settings.maxReserve());
        if (topUp <= 0.0) {
            return;
        }
        if (!economy.deposit(player, topUp)) {
            plugin.getLogger().warning("垫付失败：" + player.getName() + " +" + fmt(topUp));
            return;
        }
        FareSession session = session(player, true);
        session.reserved += topUp;
        session.touch(System.currentTimeMillis());
        debug("垫付 " + fmt(topUp) + " → " + player.getName()
                + "（目标 " + fmt(target) + "，站点 " + stop.getId() + "）");
    }

    /** 该站可乘线路中的最高"最低票价估算"。 */
    private double estimateMax(Stop stop) {
        double max = 0.0;
        boolean any = false;
        for (Line line : api.getLinesForStop(stop.getId())) {
            LineStatus status = api.getLineStatus(line.getId());
            if (status != null && !status.isBoardable()) {
                continue;
            }
            any = true;
            max = Math.max(max, estimate(line));
        }
        return any ? max : 0.0;
    }

    /** 复刻 Metro 的 getEstimatedMinimumPrice 算法（1.1.9 TicketService）。 */
    private double estimate(Line line) {
        PriceRule rule = api.getPriceRule(line.getId());
        if (rule != null) {
            double value = rule.getBasePrice();
            if (rule.getMode() == PriceRule.PricingMode.DISTANCE) {
                value += rule.getPerBlockRate();
            } else if (rule.getMode() == PriceRule.PricingMode.INTERVAL) {
                value += rule.getPerIntervalRate();
            }
            return Math.max(0.0, value);
        }
        return Math.max(0.0, line.getTicketPrice());
    }

    // ==================== 结算 ====================

    /** 标记一个新观察段（记录扣费前余额）。 */
    private void beginSegment(Player player, FareSession session) {
        session.segmentToken++;
        session.segmentBase = economy.balance(player);
        session.touch(System.currentTimeMillis());
    }

    /** 延迟回调：校验会话/段版本后结算。 */
    private void scheduleSettle(Player player, FareSession session, long token, long delayTicks, boolean endRide) {
        UUID playerId = player.getUniqueId();
        int generation = session.generation;
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            FareSession current = sessions.get(playerId);
            if (current == null || current.generation != generation || current.segmentToken != token) {
                return;
            }
            Player online = Bukkit.getPlayer(playerId);
            if (online == null || !online.isOnline()) {
                return;
            }
            settleNow(online, current, endRide);
        }, delayTicks);
    }

    /**
     * 结算当前观察段：报销 Metro 扣掉的车费；尝试回收垫付；
     * endRide 时（且玩家确实已不在车上）结束会话。
     */
    private void settleNow(Player player, FareSession session, boolean endRide) {
        Double base = session.segmentBase;
        if (base != null) {
            session.segmentBase = null;
            double current = economy.balance(player);
            double refund = FareMath.refundFor(base, current, settings.maxRefundPerSegment());
            if (refund > 0.0) {
                if (economy.deposit(player, refund)) {
                    store.addStat("refunded-total", refund);
                    store.addStat("refund-count", 1);
                    if (settings.notifyRefund()) {
                        messages.sendPrefixed(player, "refund-notify", "price", economy.format(refund));
                    }
                    debug("报销 " + fmt(refund) + " → " + player.getName());
                } else {
                    plugin.getLogger().warning("报销失败：" + player.getName() + " × " + fmt(refund));
                }
            } else if (current > base + 1e-6) {
                debug("段内余额增加（可能收到其他收入），忽略报销: " + player.getName());
            }
        }

        if (session.reserved > 0.0) {
            recycleReserve(player, session, false);
        }
        session.touch(System.currentTimeMillis());

        if (endRide && !isRidingMetroMinecart(player)) {
            finishRide(player, session);
        }
    }

    // ==================== 垫付回收 ====================

    /**
     * 回收垫付：只收回我们垫出去的钱（上限 = 尚未回收的垫付额），
     * 不动玩家自有余额（余额不足时只回收可用部分；会话结束时残留清账并告警）。
     */
    private void recycleReserve(Player player, FareSession session, boolean atEnd) {
        if (session.reserved <= 0.0) {
            return;
        }
        double balance = economy.balance(player);
        double amount = FareMath.recycle(session.reserved, balance);
        if (amount > 0.0 && economy.withdraw(player, amount)) {
            session.reserved -= amount;
            debug("回收垫付 " + fmt(amount) + " ← " + player.getName()
                    + "（剩余垫付 " + fmt(session.reserved) + "）");
        } else if (amount > 0.0) {
            plugin.getLogger().warning("回收垫付失败：" + player.getName() + " × " + fmt(amount));
        }
        if (session.reserved > 1e-6 && atEnd) {
            plugin.getLogger().warning("会话结束时仍有未回收垫付 " + fmt(session.reserved)
                    + "（" + player.getName() + "），请核对经济日志。");
            session.reserved = 0.0;
        }
    }

    private void finishRide(Player player, FareSession session) {
        recycleReserve(player, session, true);
        sessions.remove(session.playerId);
        debug("会话结束: " + player.getName());
    }

    // ==================== 看门狗 ====================

    /** 定时兜底：处理超时会话与滞留垫付。 */
    private void watchdog() {
        if (!active) {
            return;
        }
        long now = System.currentTimeMillis();
        // 用快照遍历：处理过程中会移除会话，不能直接迭代原 Map
        for (FareSession session : new ArrayList<>(sessions.values())) {
            if (!sessions.containsKey(session.playerId)) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.playerId);
            if (player == null || !player.isOnline()) {
                sessions.remove(session.playerId);
                continue;
            }
            boolean riding = isRidingMetroMinecart(player);
            long idle = now - session.lastActivityAt;
            try {
                if (!riding && idle > settings.sessionTimeoutMillis()) {
                    settleNow(player, session, true);
                    if (sessions.containsKey(session.playerId)) {
                        // settleNow 未结束会话时兜底回收并移除
                        recycleReserve(player, session, true);
                        sessions.remove(session.playerId);
                    }
                    debug("会话超时清理: " + player.getName());
                } else if (riding && session.reserved > 0.0 && idle > RIDING_RESERVE_STALE_MS) {
                    recycleReserve(player, session, false);
                } else if (riding && idle > RIDING_SESSION_MAX_MS) {
                    plugin.getLogger().warning("会话活跃时间异常，兜底清理: " + player.getName());
                    recycleReserve(player, session, true);
                    sessions.remove(session.playerId);
                }
            } catch (Throwable t) {
                plugin.getLogger().warning("看门狗异常: " + t);
                sessions.remove(session.playerId);
            }
        }
    }

    // ==================== 工具 ====================

    private FareSession session(Player player, boolean create) {
        FareSession session = sessions.get(player.getUniqueId());
        if (session == null && create) {
            int generation = generations.merge(player.getUniqueId(), 1, Integer::sum);
            session = new FareSession(player.getUniqueId(), generation, System.currentTimeMillis());
            sessions.put(player.getUniqueId(), session);
        }
        return session;
    }

    private boolean isRidingMetroMinecart(Player player) {
        Entity vehicle = player.getVehicle();
        return vehicle instanceof Minecart cart && isMetroMinecart(cart);
    }

    private static boolean isMetroMinecart(Minecart cart) {
        NamespacedKey key = MetroConstants.getMinecartKey();
        return key != null && cart.getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    private void debug(String message) {
        if (settings.debug()) {
            plugin.getLogger().info("[debug] " + message);
        }
    }

    private static String fmt(double amount) {
        return String.format("%.2f", amount);
    }
}
