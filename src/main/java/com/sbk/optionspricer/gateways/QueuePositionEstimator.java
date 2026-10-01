package com.sbk.optionspricer.gateways;

/**
 * Level 3 Market-by-Order (MBO) Queue Position & Priority Estimator.
 * Models FIFO queue priority on European exchanges (Eurex EOBI, Euronext Optiq)
 * to estimate probability of execution prior to quote injection.
 */
public final class QueuePositionEstimator {

    private QueuePositionEstimator() {}

    /**
     * Represents the status of our order resting in the order book.
     */
    public static class QueueState {
        public final int ordersAhead;
        public final int volumeAhead;
        public final double fillProbability;

        public QueueState(int ordersAhead, int volumeAhead, double fillProbability) {
            this.ordersAhead = ordersAhead;
            this.volumeAhead = volumeAhead;
            this.fillProbability = fillProbability;
        }
    }

    /**
     * Calculates estimated probability of fill given queue depth, trade velocity, and cancel rate.
     * 
     * @param ordersAhead Number of resting orders ahead in the FIFO queue
     * @param volumeAhead Total contract volume ahead in the queue
     * @param tradeRatePerSec Average contract trade rate per second
     * @param cancelRatePerSec Average contract cancellation rate per second
     * @param horizonSec Target execution horizon in seconds
     */
    public static QueueState estimateQueuePosition(int ordersAhead, int volumeAhead,
                                                   double tradeRatePerSec, double cancelRatePerSec,
                                                   double horizonSec) {
        double expectedVolumeCleared = (tradeRatePerSec + 0.5 * cancelRatePerSec) * horizonSec;
        
        double fillProb;
        if (volumeAhead <= 0) {
            fillProb = 1.0;
        } else if (expectedVolumeCleared >= volumeAhead) {
            fillProb = Math.min(1.0, 0.8 + 0.2 * (expectedVolumeCleared - volumeAhead) / volumeAhead);
        } else {
            fillProb = Math.max(0.0, expectedVolumeCleared / volumeAhead);
        }

        return new QueueState(ordersAhead, volumeAhead, fillProb);
    }
}
