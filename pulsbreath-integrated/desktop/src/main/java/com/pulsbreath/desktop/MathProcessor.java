
package com.pulsbreath.desktop;

import java.util.Arrays;

/**
 * Математичний апарат PulseBreath.
 *
 * 1. Перевірка вхідних значень.
 * 2. Фільтр Савіцького — Голея для пульсу та дихання.
 * 3. Медіанний фільтр для відстані.
 * 4. Експоненційне згладжування EMA.
 * 5. Формування підсумку за 30-секундний період.
 * 6. Вейвлетне придушення шуму Хаара для історичних даних.
 *
 * Клас не читає BLE/Wi-Fi та не працює з БД.
 */
public class MathProcessor {

    private static final double EMA_ALPHA = 0.2;
    private static final double DISTANCE_EMA_ALPHA = 0.2;

    private static final double DISTANCE_SCALE = 1.0;
    private static final double DISTANCE_OFFSET_CM = 0.0;

    private static final long PERIOD_NS = 30_000_000_000L;
    private static final long FILTER_RESET_NS = 10_000_000_000L;
    private static final double MAX_DISTANCE_CM = 600.0;

    private final SavitzkyGolayFilter pulseFilter =
            new SavitzkyGolayFilter();

    private final SavitzkyGolayFilter breathFilter =
            new SavitzkyGolayFilter();

    private final MedianFilter distanceFilter =
            new MedianFilter();

    private double pulseEma;
    private double breathEma;
    private double distanceEma;

    private boolean pulseEmaInitialized;
    private boolean breathEmaInitialized;
    private boolean distanceEmaInitialized;

    private long lastPulseNs;
    private long lastBreathNs;
    private long lastDistanceNs;

    private long periodStartNs;
    private long periodStartTimestampMs;

    private double periodPulse = Double.NaN;
    private double periodBreath = Double.NaN;
    private double periodDistance = Double.NaN;

    private double sumPulse;
    private double sumBreath;
    private double sumDistance;

    private int periodPulseCount;
    private int periodBreathCount;
    private int periodDistanceCount;

    public MathProcessor() {
    }

    /**
     * Обробляє один набір показників від сенсора.
     */
    public synchronized ProcessResult process(
            double rawPulse,
            boolean pulseValid,
            double rawBreath,
            boolean breathValid,
            double rawDistanceCm,
            boolean distanceValid) {

        long nowNs = System.nanoTime();
        long nowMs = System.currentTimeMillis();

        MinuteSummary completed = null;

        if (periodStartNs == 0L) {
            periodStartNs = nowNs;
            periodStartTimestampMs = nowMs;
        } else if (nowNs - periodStartNs >= PERIOD_NS) {
            completed = finishPeriod(nowMs);

            periodStartNs = nowNs;
            periodStartTimestampMs = nowMs;

            clearPeriodAccumulator();
        }

        pulseValid = pulseValid && validPositive(rawPulse);

        breathValid = breathValid && validPositive(rawBreath);

        distanceValid = distanceValid
                && validPositive(rawDistanceCm)
                && rawDistanceCm <= MAX_DISTANCE_CM;

        // ====================================================
        // ПУЛЬС
        // ====================================================

        if (pulseValid) {

            if (hasGap(lastPulseNs, nowNs)) {
                pulseFilter.reset();

                pulseEma = rawPulse;
                pulseEmaInitialized = true;
            }

            lastPulseNs = nowNs;

            Double filtered = pulseFilter.add(rawPulse);

            /*
             * Виправлення:
             * якщо фільтр ще накопичує п'ять значень,
             * використовуємо поточне валідне вимірювання.
             *
             * Після накопичення вікна використовуємо
             * відфільтрований результат.
             */
            double valueForEma =
                    filtered != null && validPositive(filtered)
                            ? filtered
                            : rawPulse;

            if (validPositive(valueForEma)) {

                pulseEma = ema(
                        valueForEma,
                        pulseEma,
                        pulseEmaInitialized
                );

                pulseEmaInitialized = true;

                sumPulse += pulseEma;
                periodPulse = pulseEma;
                periodPulseCount++;
            }
        }

        // ====================================================
        // ДИХАННЯ
        // ====================================================

        if (breathValid) {

            if (hasGap(lastBreathNs, nowNs)) {
                breathFilter.reset();

                breathEma = rawBreath;
                breathEmaInitialized = true;
            }

            lastBreathNs = nowNs;

            Double filtered = breathFilter.add(rawBreath);

            /*
             * Виправлення:
             * не чекаємо повного вікна фільтра,
             * щоб отримати перше значення дихання.
             */
            double valueForEma =
                    filtered != null && validPositive(filtered)
                            ? filtered
                            : rawBreath;

            if (validPositive(valueForEma)) {

                breathEma = ema(
                        valueForEma,
                        breathEma,
                        breathEmaInitialized
                );

                breathEmaInitialized = true;

                sumBreath += breathEma;
                periodBreath = breathEma;
                periodBreathCount++;
            }
        }

        // ====================================================
        // ВІДСТАНЬ
        // ====================================================

        if (distanceValid) {

            double calibratedDistance =
                    rawDistanceCm * DISTANCE_SCALE
                            + DISTANCE_OFFSET_CM;

            if (hasGap(lastDistanceNs, nowNs)) {
                distanceFilter.reset();

                distanceEma = calibratedDistance;
                distanceEmaInitialized = true;
            }

            lastDistanceNs = nowNs;

            Double filtered =
                    distanceFilter.add(calibratedDistance);

            if (filtered != null && validPositive(filtered)) {

                distanceEma = emaCustom(
                        filtered,
                        distanceEma,
                        distanceEmaInitialized,
                        DISTANCE_EMA_ALPHA
                );

                distanceEmaInitialized = true;

                System.out.printf(
                        java.util.Locale.US,
                        "DISTANCE raw=%.2f cm | median=%.2f cm | EMA=%.2f cm%n",
                        rawDistanceCm,
                        filtered,
                        distanceEma
                );

                sumDistance += distanceEma;
                periodDistance = distanceEma;
                periodDistanceCount++;
            }
        }

        // ====================================================
        // ПОТОЧНИЙ РЕЗУЛЬТАТ
        // ====================================================

        Measurement live = new Measurement(
                pulseEmaInitialized ? pulseEma : 0.0,
                pulseValid && pulseEmaInitialized,

                breathEmaInitialized ? breathEma : 0.0,
                breathValid && breathEmaInitialized,

                distanceEmaInitialized ? distanceEma : 0.0,
                distanceValid && distanceEmaInitialized,

                nowMs
        );

        return new ProcessResult(live, completed);
    }

    // ========================================================
    // ФОРМУВАННЯ ПІДСУМКУ ЗА 30 СЕКУНД
    // ========================================================

    private MinuteSummary finishPeriod(long timestampMs) {

        boolean pValid = periodPulseCount > 0;
        boolean bValid = periodBreathCount > 0;
        boolean dValid = periodDistanceCount > 0;

        if (!pValid && !bValid && !dValid) {
            return null;
        }

        double avgPulse = pValid
                ? sumPulse / periodPulseCount
                : periodPulse;

        double avgBreath = bValid
                ? sumBreath / periodBreathCount
                : periodBreath;

        double avgDistance = dValid
                ? sumDistance / periodDistanceCount
                : periodDistance;

        String pulseStatus = pValid
                ? classifyPulse(avgPulse)
                : "Недостатньо даних";

        String breathStatus = bValid
                ? classifyBreath(avgBreath)
                : "Недостатньо даних";

        String classification =
                "Пульс: " + pulseStatus
                        + "; Дихання: " + breathStatus;

        return new MinuteSummary(
                avgPulse,
                pValid,

                avgBreath,
                bValid,

                avgDistance,
                dValid,

                periodStartTimestampMs,
                timestampMs,

                pulseStatus,
                breathStatus,
                classification
        );
    }

    private void clearPeriodAccumulator() {

        periodPulse = Double.NaN;
        periodBreath = Double.NaN;
        periodDistance = Double.NaN;

        sumPulse = 0.0;
        sumBreath = 0.0;
        sumDistance = 0.0;

        periodPulseCount = 0;
        periodBreathCount = 0;
        periodDistanceCount = 0;
    }

    private boolean hasGap(long previousNs, long nowNs) {
        return previousNs > 0L
                && nowNs - previousNs > FILTER_RESET_NS;
    }

    private static boolean validPositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    // ========================================================
    // EMA
    // ========================================================

    private static double ema(
            double value,
            double previous,
            boolean initialized) {

        return initialized
                ? EMA_ALPHA * value
                        + (1.0 - EMA_ALPHA) * previous
                : value;
    }

    private static double emaCustom(
            double value,
            double previous,
            boolean initialized,
            double alpha) {

        return initialized
                ? alpha * value
                        + (1.0 - alpha) * previous
                : value;
    }

    // ========================================================
    // КЛАСИФІКАЦІЯ ПОКАЗНИКІВ
    // ========================================================

    public static String classifyPulse(double pulse) {

        if (!validPositive(pulse)) {
            return "Недостатньо даних";
        } else if (pulse < 50.0) {
            return "🔴Брадикардія";
        } else if (pulse < 60.0) {
            return "🟡 Помірна брадикардія";
        } else if (pulse < 80.0) {
            return "🟢Норма";
        } else if (pulse < 100.0) {
            return "🟡Помірна тахікардія";
        } else {
            return "🔴Тахікардія";
        }
    }

    public static String classifyBreath(double breath) {

        if (!validPositive(breath)) {
            return "Недостатньо даних";
        } else if (breath < 10.0) {
            return "🔴Виражене брадипное";
        } else if (breath < 12.0) {
            return "🟡Помірне брадипное";
        } else if (breath <= 18.0) {
            return "🟢Еупное";
        } else if (breath <= 22.0) {
            return "🟡Помірне тахіпное";
        } else {
            return "🔴Виражене тахіпное";
        }
    }

    // ========================================================
    // СКИДАННЯ СТАНУ
    // ========================================================

    public synchronized void reset() {

        pulseFilter.reset();
        breathFilter.reset();
        distanceFilter.reset();

        pulseEma = 0.0;
        breathEma = 0.0;
        distanceEma = 0.0;

        pulseEmaInitialized = false;
        breathEmaInitialized = false;
        distanceEmaInitialized = false;

        lastPulseNs = 0L;
        lastBreathNs = 0L;
        lastDistanceNs = 0L;

        periodStartNs = 0L;
        periodStartTimestampMs = 0L;

        clearPeriodAccumulator();
    }

    // ========================================================
    // ВЕЙВЛЕТНА ОБРОБКА ІСТОРИЧНИХ ДАНИХ
    // ========================================================

    public static double[] denoiseWavelet(
            double[] input,
            int maxLevels) {

        return HaarWaveletDenoiser.denoise(input, maxLevels);
    }

    // ========================================================
    // РЕЗУЛЬТАТИ ОБРОБКИ
    // ========================================================

    public static class ProcessResult {

        public final Measurement live;
        public final MinuteSummary completedMinute;

        public ProcessResult(
                Measurement live,
                MinuteSummary completedMinute) {

            this.live = live;
            this.completedMinute = completedMinute;
        }
    }

    public static class Measurement {

        public final double pulse;
        public final boolean pulseValid;

        public final double breath;
        public final boolean breathValid;

        public final double distanceCm;
        public final boolean distanceValid;

        public final long timestamp;

        public Measurement(
                double pulse,
                boolean pulseValid,
                double breath,
                boolean breathValid,
                double distanceCm,
                boolean distanceValid,
                long timestamp) {

            this.pulse = pulse;
            this.pulseValid = pulseValid;

            this.breath = breath;
            this.breathValid = breathValid;

            this.distanceCm = distanceCm;
            this.distanceValid = distanceValid;

            this.timestamp = timestamp;
        }
    }

    public static class MinuteSummary {

        public final double pulse;
        public final boolean pulseValid;

        public final double breath;
        public final boolean breathValid;

        public final double distanceCm;
        public final boolean distanceValid;

        public final long startTimestamp;
        public final long endTimestamp;

        public final String pulseStatus;
        public final String breathStatus;
        public final String pathologySuspicion;

        public MinuteSummary(
                double pulse,
                boolean pulseValid,
                double breath,
                boolean breathValid,
                double distanceCm,
                boolean distanceValid,
                long startTimestamp,
                long endTimestamp,
                String pulseStatus,
                String breathStatus,
                String pathologySuspicion) {

            this.pulse = pulse;
            this.pulseValid = pulseValid;

            this.breath = breath;
            this.breathValid = breathValid;

            this.distanceCm = distanceCm;
            this.distanceValid = distanceValid;

            this.startTimestamp = startTimestamp;
            this.endTimestamp = endTimestamp;

            this.pulseStatus = pulseStatus;
            this.breathStatus = breathStatus;
            this.pathologySuspicion = pathologySuspicion;
        }
    }

    // ========================================================
    // МЕДІАННИЙ ФІЛЬТР
    // ========================================================

    public static class MedianFilter {

        private static final int WINDOW_SIZE = 5;

        private final double[] window =
                new double[WINDOW_SIZE];

        private int count;
        private int next;

        public synchronized Double add(double value) {

            if (!Double.isFinite(value)) {
                return null;
            }

            window[next] = value;
            next = (next + 1) % WINDOW_SIZE;

            if (count < WINDOW_SIZE) {
                count++;
            }

            double[] values = Arrays.copyOf(window, count);
            Arrays.sort(values);

            if (count % 2 == 0) {
                return (
                        values[count / 2 - 1]
                                + values[count / 2]
                ) / 2.0;
            }

            return values[count / 2];
        }

        public synchronized void reset() {

            Arrays.fill(window, 0.0);

            count = 0;
            next = 0;
        }

        public synchronized void resetWith(double value) {

            Arrays.fill(window, value);

            count = WINDOW_SIZE;
            next = 0;
        }
    }

    // ========================================================
    // ФІЛЬТР САВІЦЬКОГО — ГОЛЕЯ
    // ========================================================

    public static class SavitzkyGolayFilter {

        private final double[] window = new double[5];

        private int count;
        private int next;

        public synchronized Double add(double value) {

            if (!Double.isFinite(value)) {
                return null;
            }

            window[next] = value;
            next = (next + 1) % 5;

            if (count < 5) {
                count++;
            }

            if (count < 5) {
                return null;
            }

            double[] x = new double[5];

            for (int i = 0; i < 5; i++) {
                x[i] = window[(next + i) % 5];
            }

            return (
                    -3.0 * x[0]
                            + 12.0 * x[1]
                            + 17.0 * x[2]
                            + 12.0 * x[3]
                            - 3.0 * x[4]
            ) / 35.0;
        }

        public synchronized void reset() {

            Arrays.fill(window, 0.0);

            count = 0;
            next = 0;
        }

        public synchronized void resetWith(double value) {

            Arrays.fill(window, value);

            count = 5;
            next = 0;
        }
    }

    // ========================================================
    // ВЕЙВЛЕТНИЙ ФІЛЬТР ХААРА
    // ========================================================

    public static class HaarWaveletDenoiser {

        private static final double SQRT2 = Math.sqrt(2.0);
        private static final double MAD_NORMALIZATION = 0.67448975;

        public static double[] denoise(
                double[] input,
                int maxLevels) {

            if (input == null || input.length == 0) {
                return new double[0];
            }

            double[] result = input.clone();

            for (double value : input) {
                if (!Double.isFinite(value)) {
                    return result;
                }
            }

            if (input.length < 4 || maxLevels <= 0) {
                return result;
            }

            int originalLength = input.length;
            int paddedLength = 1;

            while (paddedLength < originalLength) {

                if (paddedLength > 1_073_741_823) {
                    return result;
                }

                paddedLength *= 2;
            }

            double[] data = new double[paddedLength];

            for (int i = 0; i < paddedLength; i++) {
                data[i] = input[reflectIndex(i, originalLength)];
            }

            int safeLevels = Math.min(maxLevels, 31);

            int[] levelLengths = new int[safeLevels];

            int levels = 0;
            int activeLength = paddedLength;

            while (activeLength >= 2 && levels < safeLevels) {

                int half = activeLength / 2;
                double[] temp = new double[activeLength];

                for (int i = 0; i < half; i++) {

                    double even = data[2 * i];
                    double odd = data[2 * i + 1];

                    temp[i] = (even + odd) / SQRT2;
                    temp[half + i] = (even - odd) / SQRT2;
                }

                System.arraycopy(
                        temp, 0, data, 0, activeLength
                );

                levelLengths[levels++] = activeLength;
                activeLength = half;
            }

            if (levels == 0) {
                return result;
            }

            int detailsHalf = paddedLength / 2;

            double[] absDetails =
                    new double[paddedLength - detailsHalf];

            for (int i = 0; i < absDetails.length; i++) {
                absDetails[i] =
                        Math.abs(data[detailsHalf + i]);
            }

            double sigma =
                    median(absDetails) / MAD_NORMALIZATION;

            double lambda =
                    sigma * Math.sqrt(2.0 * Math.log(paddedLength));

            for (int level = 0; level < levels; level++) {

                int length = levelLengths[level];
                int half = length / 2;

                for (int i = half; i < length; i++) {

                    double value = data[i];

                    data[i] = Math.copySign(
                            Math.max(
                                    Math.abs(value) - lambda,
                                    0.0
                            ),
                            value
                    );
                }
            }

            for (int level = levels - 1; level >= 0; level--) {

                int length = levelLengths[level];
                int half = length / 2;

                double[] temp = new double[length];

                for (int i = 0; i < half; i++) {

                    double approx = data[i];
                    double detail = data[half + i];

                    temp[2 * i] =
                            (approx + detail) / SQRT2;

                    temp[2 * i + 1] =
                            (approx - detail) / SQRT2;
                }

                System.arraycopy(
                        temp, 0, data, 0, length
                );
            }

            System.arraycopy(
                    data, 0, result, 0, originalLength
            );

            return result;
        }

        private static int reflectIndex(
                int index,
                int length) {

            if (length <= 1) {
                return 0;
            }

            while (index >= length || index < 0) {

                if (index >= length) {
                    index = 2 * length - 2 - index;
                }

                if (index < 0) {
                    index = -index;
                }
            }

            return index;
        }

        private static double median(double[] values) {

            double[] sorted = values.clone();
            Arrays.sort(sorted);

            int middle = sorted.length / 2;

            return sorted.length % 2 == 0
                    ? (
                            sorted[middle - 1]
                                    + sorted[middle]
                    ) / 2.0
                    : sorted[middle];
        }
    }
}