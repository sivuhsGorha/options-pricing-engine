package com.sbk.optionspricer.gateways;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Time-Weighted Average Price (TWAP) Execution Algo.
 * Slices a large parent order into smaller child orders spaced evenly over a time window.
 */
public class TwapExecutionAlgo {

    public record ScheduledOrder(OrderTicket order, Instant executionTime) {}

    /**
     * Slices a parent order into a TWAP schedule.
     *
     * @param parent The large parent order
     * @param startTime The start of the execution window
     * @param duration The total duration of the TWAP window
     * @param sliceCount The number of child orders to generate
     * @return A list of scheduled child orders
     */
    public static List<ScheduledOrder> generateSchedule(OrderTicket parent, Instant startTime, Duration duration, int sliceCount) {
        if (sliceCount <= 0) {
            throw new IllegalArgumentException("Slice count must be positive");
        }
        if (parent.getQuantity() < sliceCount) {
            sliceCount = parent.getQuantity(); // Can't slice finer than 1 unit
        }

        List<ScheduledOrder> schedule = new ArrayList<>();
        
        long intervalMillis = duration.toMillis() / sliceCount;
        int baseQty = parent.getQuantity() / sliceCount;
        int remainder = parent.getQuantity() % sliceCount;

        for (int i = 0; i < sliceCount; i++) {
            int qty = baseQty + (i < remainder ? 1 : 0);
            
            OrderTicket child = new OrderTicket(
                    parent.getOrderId() + "-TWAP-" + i,
                    parent.getSymbol(),
                    parent.getSide(),
                    qty,
                    parent.getPrice(),
                    parent.getType()
            );

            Instant execTime = startTime.plusMillis(intervalMillis * i);
            schedule.add(new ScheduledOrder(child, execTime));
        }

        return schedule;
    }
}
