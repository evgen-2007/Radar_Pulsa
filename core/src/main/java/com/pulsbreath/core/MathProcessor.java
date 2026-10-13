package com.pulsbreath.core;

import java.util.Arrays;

/**
 * MathProcessor
 *
 * Живий аналіз:
 * 1. Перевірка вхідних значень.
 * 2. Фільтр Савіцького — Голея.
 * 3. Експоненційне згладжування EMA.
 * 4. Підсумок EMA за кожну хвилину.
 *
 * Постфактум:
 * 5. Вейвлет Хаара для історичних масивів.
 *
 * БД тут не відкривається.
 * ProcessResult повертає підсумок хвилини,
 * який потрібно зберегти в DatabaseManager.
 */
public class MathProcessor {

    private static final double EMA_ALPHA = 0.2;

    private static final long MINUTE_NS = 60_000_000_000L;
    private static final long FILTER_RESET_NS = 10_000_000_000L;

    // Максимальна відстань — технічна межа цього проєкту.
    private static final double MAX_DISTANCE_CM = 600.0;

    private final SavitzkyGolayFilter pulseFilter =
            new SavitzkyGolayFilter();

    private final SavitzkyGolayFilter breathFilter =
            new SavitzkyGolayFilter();

    private final SavitzkyGolayFilter distanceFilter =
            new SavitzkyGolayFilter();

    private double pulseEma;
    private double breathEma;
    private double distanceEma;

    private boolean pulseEmaInitialized;
    private boolean breathEmaInitialized;
    private boolean distanceEmaInitialized;

    private long lastPulseNs;
    private long lastBreathNs;
    private long lastDistanceNs;

    private long minuteStartNs;
    private long minuteStartTimestampMs;

    // Останні EMA-значення, отримані протягом поточної хвилини.
    private double minutePulse = Double.NaN;
    private double minuteBreath = Double.NaN;
    private double minuteDistance = Double.NaN;

    private int minutePulseCount;
    private int minuteBreathCount;
    private int minuteDistanceCount;

    /**
     * Викликати один раз на кожне отримане BLE-повідомлення.
     *
     * live — для поточного інтерфейсу.
     * completedMinute — заповнюється тільки після завершення
     * 60-секундного інтервалу, якщо є валідні дані.
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

        if (minuteStartNs == 0L) {
            minuteStartNs = nowNs;
            minuteStartTimestampMs = nowMs;
        } else if (nowNs - minuteStartNs >= MINUTE_NS) {
            completed = finishMinute(nowMs);

            // Починаємо новий інтервал.
            minuteStartNs = nowNs;
            minuteStartTimestampMs = nowMs;
            clearMinuteAccumulator();
        }

        // Не використовуємо фіксовану верхню межу пульсу
        // чи частоти дихання для відкидання значень.
        pulseValid = pulseValid && validPositive(rawPulse);
        breathValid = breathValid && validPositive(rawBreath);

        distanceValid = distanceValid
                && validPositive(rawDistanceCm)
                && rawDistanceCm <= MAX_DISTANCE_CM;

        if (pulseValid) {
            if (hasGap(lastPulseNs, nowNs)) {
                pulseFilter.reset();
                pulseEmaInitialized = false;
            }

            lastPulseNs = nowNs;

            Double filtered = pulseFilter.add(rawPulse);

            if (filtered != null && validPositive(filtered)) {
                pulseEma = ema(
                        filtered, pulseEma, pulseEmaInitialized);
                pulseEmaInitialized = true;

                minutePulse = pulseEma;
                minutePulseCount++;
            }
        }

        if (breathValid) {
            if (hasGap(lastBreathNs, nowNs)) {
                breathFilter.reset();
                breathEmaInitialized = false;
            }

            lastBreathNs = nowNs;

            Double filtered = breathFilter.add(rawBreath);

            if (filtered != null && validPositive(filtered)) {
                breathEma = ema(
                        filtered, breathEma, breathEmaInitialized);
                breathEmaInitialized = true;

                minuteBreath = breathEma;
                minuteBreathCount++;
            }
        }

        if (distanceValid) {
            if (hasGap(lastDistanceNs, nowNs)) {
                distanceFilter.reset();
                distanceEmaInitialized = false;
            }

            lastDistanceNs = nowNs;

            Double filtered = distanceFilter.add(rawDistanceCm);

            if (filtered != null && validPositive(filtered)) {
                distanceEma = ema(
                        filtered, distanceEma, distanceEmaInitialized);
                distanceEmaInitialized = true;

                minuteDistance = distanceEma;
                minuteDistanceCount++;
            }
        }

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

    /**
     * Завершує поточну хвилину.
     * Якщо не було жодного придатного результату EMA,
     * повертає null — запису в БД не буде.
     */
    private MinuteSummary finishMinute(long timestampMs) {
        boolean pValid = minutePulseCount > 0;
        boolean bValid = minuteBreathCount > 0;
        boolean dValid = minuteDistanceCount > 0;

        if (!pValid && !bValid && !dValid) {
            return null;
        }

        String pulseStatus = pValid
                ? classifyPulse(minutePulse)
                : "Недостатньо даних";

        String breathStatus = bValid
                ? classifyBreath(minuteBreath)
                : "Недостатньо даних";

        String classification =
                "Пульс: " + pulseStatus
                + "; Дихання: " + breathStatus;

        return new MinuteSummary(
                minutePulse,
                pValid,
                minuteBreath,
                bValid,
                minuteDistance,
                dValid,
                minuteStartTimestampMs,
                timestampMs,
                pulseStatus,
                breathStatus,
                classification
        );
    }

    private void clearMinuteAccumulator() {
        minutePulse = Double.NaN;
        minuteBreath = Double.NaN;
        minuteDistance = Double.NaN;

        minutePulseCount = 0;
        minuteBreathCount = 0;
        minuteDistanceCount = 0;
    }

    private boolean hasGap(long previousNs, long nowNs) {
        return previousNs > 0
                && nowNs - previousNs > FILTER_RESET_NS;
    }

    private static boolean validPositive(double value) {
        return Double.isFinite(value) && value > 0.0;
    }

    private static double ema(
            double value,
            double previous,
            boolean initialized) {

        return initialized
                ? EMA_ALPHA * value + (1.0 - EMA_ALPHA) * previous
                : value;
    }

    /**
     * Класифікація за порогами, заданими користувачем.
     */
    public static String classifyPulse(double pulse) {
        if (!validPositive(pulse)) {
            return "Недостатньо даних";
        }

        if (pulse < 60.0) {
            return "Брадикардія";
        } else if (pulse < 80.0) {
            return "Норма";
        } else if (pulse < 100.0) {
            return "Помірна тахікардія";
        } else {
            return "Тахікардія";
        }
    }

    public static String classifyBreath(double breath) {
        if (!validPositive(breath)) {
            return "Недостатньо даних";
        }

        if (breath < 10.0) {
            return "Виражене брадипное";
        } else if (breath < 12.0) {
            return "Помірне брадипное";
        } else if (breath <= 18.0) {
            return "Еупное";
        } else if (breath <= 22.0) {
            return "Помірне тахіпное";
        } else {
            return "Виражене тахіпное";
        }
    }

    /**
     * Скидання всього стану аналізу.
     */
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

        minuteStartNs = 0L;
        minuteStartTimestampMs = 0L;

        clearMinuteAccumulator();
    }

    // --------------------------------------------------------
    // Результати
    // --------------------------------------------------------

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

    /**
     * Підсумок одного завершеного хвилинного інтервалу.
     */
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

    // --------------------------------------------------------
    // Savitzky-Golay: 5 точок, квадратичний поліном
    // --------------------------------------------------------

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

            // Центроване вікно: результат відповідає
            // третьому відліку з п'яти.
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
    }

    // --------------------------------------------------------
    // Вейвлет Хаара: постфактум
    // --------------------------------------------------------

    public static double[] denoiseWavelet(
            double[] input,
            int maxLevels) {

        return HaarWaveletDenoiser.denoise(input, maxLevels);
    }

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
                    // Пропуски потрібно обробити до вейвлет-аналізу.
                    return result;
                }
            }

            if (input.length < 4 || maxLevels <= 0) {
                return result;
            }

            int originalLength = input.length;
            int paddedLength = 1;

            while (paddedLength < originalLength) {
                if (paddedLength > Integer.MAX_VALUE / 2) {
                    return result;
                }
                paddedLength *= 2;
            }

            double[] data = new double[paddedLength];

            for (int i = 0; i < paddedLength; i++) {
                data[i] = input[reflectIndex(i, originalLength)];
            }

            int safeLevels = Math.min(
                    maxLevels,
                    31
            );

            int[] levelLengths = new int[safeLevels];
            int levels = 0;
            int activeLength = paddedLength;

            // Пряме перетворення.
            while (activeLength >= 2 && levels < safeLevels) {
                int half = activeLength / 2;
                double[] temp = new double[activeLength];

                for (int i = 0; i < half; i++) {
                    double even = data[2 * i];
                    double odd = data[2 * i + 1];

                    temp[i] = (even + odd) / SQRT2;
                    temp[half + i] = (even - odd) / SQRT2;
                }

                System.arraycopy(temp, 0, data, 0, activeLength);

                levelLengths[levels++] = activeLength;
                activeLength = half;
            }

            if (levels == 0) {
                return result;
            }

            // Оцінка шуму за MAD найдрібніших деталей.
            int finestStart = paddedLength / 2;
            double[] absDetails =
                    new double[paddedLength - finestStart];

            for (int i = 0; i < absDetails.length; i++) {
                absDetails[i] = Math.abs(data[finestStart + i]);
            }

            double sigma = median(absDetails) / MAD_NORMALIZATION;
            double lambda = sigma
                    * Math.sqrt(2.0 * Math.log(paddedLength));

            // М'яке порогування деталізуючих коефіцієнтів.
            for (int level = 0; level < levels; level++) {
                int length = levelLengths[level];
                int half = length / 2;

                for (int i = half; i < length; i++) {
                    double value = data[i];

                    data[i] = Math.copySign(
                            Math.max(Math.abs(value) - lambda, 0.0),
                            value
                    );
                }
            }

            // Зворотне перетворення.
            for (int level = levels - 1; level >= 0; level--) {
                int length = levelLengths[level];
                int half = length / 2;
                double[] temp = new double[length];

                for (int i = 0; i < half; i++) {
                    double approx = data[i];
                    double detail = data[half + i];

                    temp[2 * i] = (approx + detail) / SQRT2;
                    temp[2 * i + 1] = (approx - detail) / SQRT2;
                }

                System.arraycopy(temp, 0, data, 0, length);
            }

            System.arraycopy(data, 0, result, 0, originalLength);
            return result;
        }

        private static int reflectIndex(int index, int length) {
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

            if (sorted.length % 2 == 0) {
                return (sorted[middle - 1] + sorted[middle]) / 2.0;
            }

            return sorted[middle];
        }
    }
}