package cn.apcraft.fare;

/**
 * "垫付-报销"机制的纯数学部分，便于单元测试。
 *
 * <p>背景：Metro 本体没有票价钩子，无法直接豁免车费。MetroPass 采用
 * 账户对冲的方式模拟免费乘车：</p>
 * <ol>
 *   <li><b>垫付</b>：乘车前把玩家余额补足到"最低票价估算 + 缓冲"，
 *       让 Metro 的余额检查与扣费能够成功（避免因零余额被拒载）。</li>
 *   <li><b>报销</b>：在扣费完成后比对观察窗口前后的余额差量，
 *       把被 Metro 扣走的部分原额退还给玩家。</li>
 *   <li><b>回收</b>：垫付的临时资金在完成使命后收回，只保留玩家自有余额。</li>
 * </ol>
 */
public final class FareMath {

    private FareMath() {
    }

    /**
     * 计算本次应报销的金额。
     *
     * <p>观察窗口内余额从 {@code base} 降到 {@code current}，差额即视为
     * Metro 扣除的车费；差额非正（没扣费/余额增加）或超过上限（异常数据）时不报销。</p>
     *
     * @param base      扣费前余额
     * @param current   结算时余额
     * @param maxRefund 单次报销上限（防止误判造成大额发放）
     * @return 应报销金额，0 表示不报销
     */
    public static double refundFor(double base, double current, double maxRefund) {
        double delta = base - current;
        if (delta <= 0.0 || delta > maxRefund) {
            return 0.0;
        }
        return delta;
    }

    /**
     * 计算为达到目标余额还需要垫付的金额，受单次垫付上限约束。
     *
     * @param balance    当前余额
     * @param target     目标余额（票价估算 + 缓冲）
     * @param maxReserve 单次垫付上限
     * @return 需要垫付的金额，0 表示无需垫付
     */
    public static double topUp(double balance, double target, double maxReserve) {
        double need = target - balance;
        if (need <= 0.0) {
            return 0.0;
        }
        return Math.min(need, maxReserve);
    }

    /**
     * 计算可回收的垫付金额：不超过累计垫付额，也不超过当前余额
     * （避免把玩家自有资金一并扣走；余额不足时只回收可用部分）。
     *
     * @param reserved 尚未回收的垫付额
     * @param balance  当前余额
     * @return 可回收金额
     */
    public static double recycle(double reserved, double balance) {
        if (reserved <= 0.0 || balance <= 0.0) {
            return 0.0;
        }
        return Math.min(reserved, balance);
    }
}
