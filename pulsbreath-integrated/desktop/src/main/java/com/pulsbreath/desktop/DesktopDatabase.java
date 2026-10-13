package com.pulsbreath.desktop;

import com.pulsbreath.core.MathProcessor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** SQLite archive for the PulseBreath desktop application. */
public final class DesktopDatabase implements AutoCloseable {
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private final Connection connection;

    public DesktopDatabase() throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".pulsbreath");
        Files.createDirectories(dir);
        Path dbFile = dir.resolve("pulsbreath_archive.db");
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
        try (Statement st = connection.createStatement()) {
            st.execute("PRAGMA busy_timeout = 5000");
            st.execute("PRAGMA foreign_keys = ON");
            st.execute("CREATE TABLE IF NOT EXISTS measurements (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "patient_name TEXT NOT NULL," +
                    "timestamp INTEGER NOT NULL," +
                    "measurement_date TEXT NOT NULL," +
                    "measurement_time TEXT NOT NULL," +
                    "pulse REAL NULL," +
                    "breath REAL NULL," +
                    "distance_cm REAL NULL," +
                    "suspicion TEXT NOT NULL)");
        }
    }

    /** Saves a manual snapshot. Invalid/unavailable metrics are stored as SQL NULL. */
    public synchronized long save(String patientName, double pulse, double breath,
                                   double distanceCm, String suspicion) throws SQLException {
        long now = System.currentTimeMillis();
        String sql = "INSERT INTO measurements(patient_name,timestamp,measurement_date,measurement_time,pulse,breath,distance_cm,suspicion) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normalizePatient(patientName));
            ps.setLong(2, now);
            ps.setString(3, DATE_FMT.format(Instant.ofEpochMilli(now)));
            ps.setString(4, TIME_FMT.format(Instant.ofEpochMilli(now)));
            putMetric(ps, 5, pulse, 250.0);
            putMetric(ps, 6, breath, 60.0);
            putMetric(ps, 7, distanceCm, 600.0);
            ps.setString(8, suspicion == null || suspicion.isBlank() ? "Недостатньо даних" : suspicion);
            int changed = ps.executeUpdate();
            if (changed != 1) return -1L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    /** Saves the end-of-period summary created by MathProcessor. */
    public synchronized long save(String patientName, MathProcessor.MinuteSummary m) throws SQLException {
        if (m == null) throw new IllegalArgumentException("Підсумок вимірювання не може бути null");
        double p = m.pulseValid ? m.pulse : 0.0;
        double b = m.breathValid ? m.breath : 0.0;
        double d = m.distanceValid ? m.distanceCm : 0.0;
        String suspicion = m.pathologySuspicion == null ? "Недостатньо даних" : m.pathologySuspicion;
        long now = m.endTimestamp > 0 ? m.endTimestamp : System.currentTimeMillis();
        String sql = "INSERT INTO measurements(patient_name,timestamp,measurement_date,measurement_time,pulse,breath,distance_cm,suspicion) VALUES(?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, normalizePatient(patientName));
            ps.setLong(2, now);
            ps.setString(3, DATE_FMT.format(Instant.ofEpochMilli(now)));
            ps.setString(4, TIME_FMT.format(Instant.ofEpochMilli(now)));
            putMetric(ps, 5, p, 250.0);
            putMetric(ps, 6, b, 60.0);
            putMetric(ps, 7, d, 600.0);
            ps.setString(8, suspicion);
            if (ps.executeUpdate() != 1) return -1L;
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) return keys.getLong(1);
            }
        }
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT last_insert_rowid()")) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }

    public synchronized List<MeasurementRow> getAllMeasurements() throws SQLException {
        List<MeasurementRow> rows = new ArrayList<>();
        String sql = "SELECT id,patient_name,measurement_date,measurement_time,pulse,breath,distance_cm,suspicion FROM measurements ORDER BY id DESC";
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                rows.add(new MeasurementRow(rs.getInt("id"), rs.getString("patient_name"),
                        rs.getString("measurement_date"), rs.getString("measurement_time"),
                        nullableDouble(rs, "pulse"), nullableDouble(rs, "breath"),
                        nullableDouble(rs, "distance_cm"), rs.getString("suspicion")));
            }
        }
        return rows;
    }

    public synchronized void clearAllData() throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.executeUpdate("DELETE FROM measurements");
            st.executeUpdate("DELETE FROM sqlite_sequence WHERE name='measurements'");
        }
    }

    @Override public synchronized void close() throws SQLException {
        if (connection != null && !connection.isClosed()) connection.close();
    }

    private static void putMetric(PreparedStatement ps, int index, double value, double max) throws SQLException {
        if (Double.isFinite(value) && value > 0.0 && value <= max) ps.setDouble(index, value);
        else ps.setNull(index, java.sql.Types.REAL);
    }

    private static double nullableDouble(ResultSet rs, String column) throws SQLException {
        double value = rs.getDouble(column);
        return rs.wasNull() ? 0.0 : value;
    }

    private static String normalizePatient(String name) {
        return name == null || name.isBlank() ? "Невідомий" : name.trim();
    }

    public static final class MeasurementRow {
        private final int id;
        private final String patientName, date, time, suspicion;
        private final double pulse, breath, distance;

        public MeasurementRow(int id, String patientName, String date, String time,
                              double pulse, double breath, double distance, String suspicion) {
            this.id = id;
            this.patientName = patientName == null ? "Невідомий" : patientName;
            this.date = date == null ? "" : date;
            this.time = time == null ? "" : time;
            this.pulse = pulse;
            this.breath = breath;
            this.distance = distance;
            this.suspicion = suspicion == null ? "Недостатньо даних" : suspicion;
        }
        public int getId() { return id; }
        public String getPatientName() { return patientName; }
        public String getDate() { return date; }
        public String getTime() { return time; }
        public double getPulse() { return pulse; }
        public double getBreath() { return breath; }
        public double getDistance() { return distance; }
        public String getSuspicion() { return suspicion; }
    }
}