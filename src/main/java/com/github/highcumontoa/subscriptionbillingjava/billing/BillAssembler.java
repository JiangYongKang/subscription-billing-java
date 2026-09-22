package com.github.highcumontoa.subscriptionbillingjava.billing;

import com.github.highcumontoa.subscriptionbillingjava.model.Bill;
import com.github.highcumontoa.subscriptionbillingjava.model.BillLineItem;
import com.github.highcumontoa.subscriptionbillingjava.model.Plan;
import com.github.highcumontoa.subscriptionbillingjava.model.Segment;
import com.github.highcumontoa.subscriptionbillingjava.model.Subscription;
import com.github.highcumontoa.subscriptionbillingjava.model.UsageEvent;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 账单装配：每个套餐片段产出基础费明细与超额费明细；
 * 账单总额严格等于各明细之和（守恒）。同一组输入反复装配结果一致（稳定 key 与顺序）。
 */
@Component
public class BillAssembler {

    private final ProrationCalculator prorationCalculator;
    private final UsageAggregator usageAggregator;

    public BillAssembler(ProrationCalculator prorationCalculator, UsageAggregator usageAggregator) {
        this.prorationCalculator = prorationCalculator;
        this.usageAggregator = usageAggregator;
    }

    public void assemble(Bill bill, Subscription subscription, Map<String, Plan> plans,
                         List<UsageEvent> events) {
        Instant periodStart = bill.getPeriodStart();
        Instant periodEnd = bill.getPeriodEnd();

        List<Segment> periodSegments = periodSegments(subscription, periodStart, periodEnd);
        List<SegmentUsage> usage = usageAggregator.aggregate(events, periodSegments, plans,
                periodStart, periodEnd);

        bill.getLineItems().clear();
        long total = 0L;
        for (int i = 0; i < usage.size(); i++) {
            SegmentUsage su = usage.get(i);
            bill.getLineItems().add(new BillLineItem(
                    "BASE-" + i,
                    "基础费 套餐=" + su.planCode()
                            + " 片段=" + su.start() + "/" + su.end(),
                    su.baseFeeCents()));
            total += su.baseFeeCents();
            if (su.overageFeeCents() > 0) {
                bill.getLineItems().add(new BillLineItem(
                        "OVERAGE-" + i,
                        "超额费 套餐=" + su.planCode()
                                + " 用量=" + su.quantity()
                                + " 配额=" + su.proratedQuota()
                                + " 超额=" + su.overageQuantity(),
                        su.overageFeeCents()));
                total += su.overageFeeCents();
            }
        }
        bill.setTotalCents(total);
    }

    private List<Segment> periodSegments(Subscription subscription, Instant periodStart,
                                         Instant periodEnd) {
        List<Segment> intersect = new ArrayList<>();
        for (Segment s : subscription.getSegments()) {
            if (s.end().isAfter(periodStart) && s.start().isBefore(periodEnd)) {
                intersect.add(s);
            }
        }
        if (intersect.isEmpty()) {
            intersect.add(new Segment(subscription.getPlanCode(), periodStart, periodEnd, null));
        }
        return List.copyOf(intersect);
    }
}
