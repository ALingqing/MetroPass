package cn.apcraft.fare;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 垫付-报销-回收 的账目数学测试。
 *
 * <p>这三条规则的组合保证"玩家净支出 = 0、插件池仅承担 Metro 实际扣费"，
 * 任何一处错误都会造成刷钱或玩家亏钱，因此必须覆盖边界。</p>
 */
class FareMathTest {

    private static final double EPS = 1e-9;

    @Test
    void refundForReturnsPositiveDelta() {
        assertEquals(2.0, FareMath.refundFor(100.0, 98.0, 1000.0), EPS);
        assertEquals(2.25, FareMath.refundFor(10.5, 8.25, 1000.0), EPS);
    }

    @Test
    void refundForIgnoresNonCharges() {
        assertEquals(0.0, FareMath.refundFor(100.0, 100.0, 1000.0), EPS);
        assertEquals(0.0, FareMath.refundFor(100.0, 102.0, 1000.0), EPS);
    }

    @Test
    void refundForRespectsCap() {
        assertEquals(0.0, FareMath.refundFor(100.0, 0.0, 50.0), EPS);
        assertEquals(50.0, FareMath.refundFor(100.0, 50.0, 50.0), EPS);
    }

    @Test
    void topUpFillsGapOnly() {
        assertEquals(55.0, FareMath.topUp(50.0, 105.0, 1000.0), EPS);
        assertEquals(0.0, FareMath.topUp(200.0, 105.0, 1000.0), EPS);
        assertEquals(0.0, FareMath.topUp(105.0, 105.0, 1000.0), EPS);
    }

    @Test
    void topUpRespectsMaxReserve() {
        assertEquals(500.0, FareMath.topUp(0.0, 100000.0, 500.0), EPS);
    }

    @Test
    void recycleCapsAtReservedAndBalance() {
        assertEquals(30.0, FareMath.recycle(30.0, 100.0), EPS);
        assertEquals(10.0, FareMath.recycle(30.0, 10.0), EPS);
        assertEquals(0.0, FareMath.recycle(0.0, 100.0), EPS);
        assertEquals(0.0, FareMath.recycle(30.0, 0.0), EPS);
        assertEquals(0.0, FareMath.recycle(30.0, -5.0), EPS);
    }

    /**
     * 端到端对账：B0 → 垫付 X → 扣费 P → 报销 P → 回收 X，
     * 玩家余额回到 B0，插件净支出恰为 P。
     */
    @Test
    void endToEndBalanceSheet() {
        double b0 = 10.0;
        double estimate = 105.0;
        double charge = 100.0;

        double reserve = FareMath.topUp(b0, estimate, 10000.0);
        assertEquals(95.0, reserve, EPS);
        double afterReserve = b0 + reserve;

        double afterCharge = afterReserve - charge;

        double refund = FareMath.refundFor(afterReserve, afterCharge, 10000.0);
        assertEquals(charge, refund, EPS);
        double afterRefund = afterCharge + refund;

        double recycled = FareMath.recycle(reserve, afterRefund);
        assertEquals(reserve, recycled, EPS);
        double finalBalance = afterRefund - recycled;

        assertEquals(b0, finalBalance, EPS);
        assertEquals(charge, reserve - recycled + refund, EPS);
    }
}
