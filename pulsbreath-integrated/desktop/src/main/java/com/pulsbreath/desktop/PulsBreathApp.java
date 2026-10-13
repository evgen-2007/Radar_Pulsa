package com.pulsbreath.desktop;

import com.pulsbreath.desktop.MathProcessor.*;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.chart.LineChart;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.stage.FileChooser;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

public class PulsBreathApp extends Application {

    private static final String APP_TITLE = "PulsBreath";
    private static final String DEFAULT_AP_IP = "192.168.4.1";
    private static final int HTTP_TIMEOUT_MS = 700;
    private static final int MAX_CHART_POINTS = 60;

    private final MathProcessor mathProcessor = new MathProcessor();

    private final ScheduledExecutorService pollingExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread thread = new Thread(r, "pulsbreath-polling");
                thread.setDaemon(true);
                return thread;
            });

    private final ExecutorService discoveryExecutor =
            Executors.newFixedThreadPool(32, r -> {
                Thread thread = new Thread(r, "pulsbreath-discovery");
                thread.setDaemon(true);
                return thread;
            });

    private DatabaseManager dbManager;
    private PythonBleClient bleClient;
    private volatile boolean bleTelemetryActive = false;

    private Stage primaryStage;
    private TabPane tabPane;

    // ПАЦІЄНТИ
    private ComboBox<Patient> patientComboBox;
    private TextField patientNameField;

    private TableView<Patient> patientsTable;
    private final ObservableList<Patient> patientsList =
            FXCollections.observableArrayList();

    // ГОЛОВНА СТОРІНКА
    private Label heartValueLabel;
    private Label breathValueLabel;
    private Label distanceValueLabel;

    private Label connectionStatusLabel;
    private Label sensorStatusLabel;
    private Label recordingStatusLabel;

    private Button discoverButton;
    private Button monitoringButton;
    private Button deviceRecordingButton;

    private LineChart<Number, Number> pulseChart;
    private LineChart<Number, Number> breathChart;

    private XYChart.Series<Number, Number> pulseSeries;
    private XYChart.Series<Number, Number> breathSeries;

    private long chartTimeIndex = 0;

    private volatile boolean monitoringEnabled = false;
    private volatile boolean discoveryRunning = false;
    private volatile boolean pollingScheduled = false;
    private volatile boolean deviceRecording = false;

    private volatile String deviceBaseUrl = null;
    private volatile SensorSnapshot latestSnapshot = null;
    private volatile long latestSnapshotReceivedAt = 0;

    // ТАБЛИЦІ ДАНИХ ТА АРХІВУ
    private TableView<RawRecord> rawDataTable;

    private final ObservableList<RawRecord> rawDataList =
            FXCollections.observableArrayList();

    private TableView<ArchiveRecord> archiveTable;

    private final ObservableList<ArchiveRecord> archiveList =
            FXCollections.observableArrayList();

    private Label archiveCountLabel;

    // Елементи пошуку в архіві
    private ComboBox<Patient> archivePatientComboBox;
    private ComboBox<LocalDate> archiveDateComboBox;

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");

    // ЗАПУСК ЗАСТОСУНКУ
    @Override
    public void start(Stage stage) {
        this.primaryStage = stage;

        dbManager = new DatabaseManager();
        dbManager.initDatabase();

        tabPane = new TabPane();

        Tab homeTab = new Tab(
                "🏠Головна сторінка",
                createHomeView()
        );

        Tab databaseTab = new Tab(
                "💾База даних",
                createDatabaseView()
        );

        Tab archiveTab = new Tab(
                "🕓Архів",
                createArchiveView()
        );

        homeTab.setClosable(false);
        databaseTab.setClosable(false);
        archiveTab.setClosable(false);

        tabPane.getTabs().addAll(
                homeTab,
                databaseTab,
                archiveTab
        );

        Scene scene = new Scene(tabPane, 1180, 820);

        scene.getStylesheets().add(
           getClass().getResource("/style.css").toExternalForm()
        );

        stage.setTitle(APP_TITLE + " — монітор пацієнта");
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        stage.setScene(scene);
        stage.show();

        refreshPatientControls();
        refreshPatientsTable();
        refreshArchivePatientControls();
        refreshArchiveTable();

        // BLE: приймання JSON від Python-містка.
        bleClient = new PythonBleClient(new PythonBleClient.Listener() {
            @Override
            public void onJson(String json) {
                try {
                    SensorSnapshot snapshot = parseSensorJson(json);

                    // Дані обробляються в JavaFX-потоці.
                    Platform.runLater(() -> {
                        bleTelemetryActive = true;

                        connectionStatusLabel.setText(
                            snapshot.bleConnected
                                ? "Сенсор підключено через BLE"
                                : "BLE: очікування підключення"
                        );
                        acceptSensorSnapshot(snapshot);
                    });

                } catch (Exception ex) {
                    onStatus("BLE: помилка обробки JSON: "
                        + ex.getMessage());
                }
            }

            @Override
            public void onStatus(String text) {
                Platform.runLater(() -> {
                    if (connectionStatusLabel != null) {
                        connectionStatusLabel.setText(text);
                    }
                });
            }
        });

        bleClient.start();
        // HTTP-пошук залишаємо для доступу до API пристрою.
        discoverDeviceAsync(false);
    }

    // ВКЛАДКА 1 — ГОЛОВНА СТОРІНКА
    private VBox createHomeView() {
        VBox root = new VBox(12);
        root.setPadding(new Insets(15));

        // Рядок вибору та додавання пацієнта
        HBox patientRow = new HBox(10);
        patientRow.setAlignment(Pos.CENTER_LEFT);

        patientComboBox = new ComboBox<>();
        patientComboBox.setPromptText("Оберіть пацієнта");
        patientComboBox.setPrefWidth(220);

        patientNameField = new TextField();
        patientNameField.setPromptText("ПІБ нового пацієнта");
        patientNameField.setPrefWidth(240);

        Button addPatientButton = new Button("Додати пацієнта");
        addPatientButton.setOnAction(e -> addPatientFromHome());

        patientRow.getChildren().addAll(
                new Label("👤Пацієнт:"),
                patientComboBox,
                patientNameField,
                addPatientButton
        );

        // Картки показників
        GridPane metrics = new GridPane();
        metrics.setHgap(12);
        metrics.setVgap(12);

        ColumnConstraints c1 = new ColumnConstraints();
        ColumnConstraints c2 = new ColumnConstraints();
        ColumnConstraints c3 = new ColumnConstraints();

        c1.setPercentWidth(33.33);
        c2.setPercentWidth(33.33);
        c3.setPercentWidth(33.34);

        metrics.getColumnConstraints().addAll(c1, c2, c3);

        metrics.add(
                createMetricCard("🫀Пульс", "—", "уд/хв", 0),
                0, 0
        );

        metrics.add(
                createMetricCard("🫁Частота дихання", "—", "вдихів/хв", 1),
                1, 0
        );

        metrics.add(
                createMetricCard("📡Відстань", "—", "см", 2),
                2, 0
        );

        // Графіки
        HBox charts = new HBox(12);
        charts.setAlignment(Pos.CENTER);

        pulseChart = createChart(
                "Пульс",
                "Час (відліки)",
                "уд/хв",
                40, 160, 10
        );

        breathChart = createChart(
                "Частота дихання",
                "Час (відліки)",
                "вдихів/хв",
                0, 40, 5
        );

        pulseSeries = new XYChart.Series<>();
        pulseSeries.setName("Пульс");

        breathSeries = new XYChart.Series<>();
        breathSeries.setName("Дихання");

        pulseChart.getData().add(pulseSeries);
        breathChart.getData().add(breathSeries);

        HBox.setHgrow(pulseChart, Priority.ALWAYS);
        HBox.setHgrow(breathChart, Priority.ALWAYS);

        charts.getChildren().addAll(
                pulseChart,
                breathChart
        );

        VBox.setVgrow(charts, Priority.ALWAYS);

        // Статус сенсора
        HBox connectionRow = new HBox(10);
        connectionRow.setAlignment(Pos.CENTER_LEFT);

        connectionStatusLabel = new Label("Сенсор: пошук…");
        sensorStatusLabel = new Label("Очікування вимірювань");
        recordingStatusLabel = new Label("Запис на пристрої: невідомо");

        connectionRow.getChildren().addAll(
                connectionStatusLabel,
                new Separator(),
                sensorStatusLabel,
                new Separator(),
                recordingStatusLabel
        );

        // Кнопки керування
        HBox actionRow = new HBox(10);
        actionRow.setAlignment(Pos.CENTER_LEFT);

        discoverButton = new Button("🔍Знайти сенсор");
        discoverButton.setOnAction(e -> discoverDeviceAsync(true));

        monitoringButton = new Button("▶️Почати моніторинг");
        monitoringButton.setOnAction(e -> toggleMonitoring());

        deviceRecordingButton = new Button("⏺️Почати запис на пристрої");
        deviceRecordingButton.setOnAction(e -> toggleDeviceRecording());

        Button archiveCurrentButton =
                new Button("🗃️Внести поточні дані до архіву");

        archiveCurrentButton.setOnAction(e -> archiveCurrentData());

        actionRow.getChildren().addAll(
                discoverButton,
                monitoringButton,
                deviceRecordingButton,
                archiveCurrentButton
        );

        Label note = new Label(
                "Підключення: автоматичний пошук через HTTP API ESP32-C6. " +
                "Показники відображаються лише за наявності валідних даних."
        );

        note.setWrapText(true);
        note.setStyle(
                "-fx-text-fill: #777777; -fx-font-size: 11px;"
        );

        root.getChildren().addAll(
                patientRow,
                metrics,
                charts,
                connectionRow,
                actionRow,
                note
        );

        return root;
    }

    private VBox createMetricCard(
            String title,
            String initialValue,
            String unit,
            int metricIndex
    ) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(15));
        card.setMinHeight(100);

        card.setStyle(
                "-fx-background-color: #0d1727;" +
                "-fx-border-color: #24465e;" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 10;" +
                "-fx-background-radius: 10;" +
                "-fx-effect: dropshadow(gaussian, #00e5ff22, 12, 0.2, 0, 0);"
        );

        Label titleLabel = new Label(title);
        titleLabel.setStyle(
                "-fx-font-size: 13px; -fx-font-weight: bold;"
        );

        Label valueLabel = new Label(initialValue);
        String neonColor = switch (metricIndex) {
            case 0 -> "#00e5ff";  // Пульс — бірюзовий
            case 1 -> "#b388ff";  // Дихання — фіолетовий
            case 2 -> "#39ffb6";  // Відстань — зелений
            default -> "#00e5ff";
        };

        valueLabel.setStyle(
            "-fx-font-size: 30px;" +
            "-fx-font-weight: bold;" +
            "-fx-text-fill: " + neonColor + ";" +
            "-fx-effect: dropshadow(gaussian, " +
            neonColor + "99, 16, 0.35, 0, 0);"
        );

        Label unitLabel = new Label(unit);
        unitLabel.setStyle("-fx-text-fill: #777777;");

        switch (metricIndex) {
            case 0 -> heartValueLabel = valueLabel;
            case 1 -> breathValueLabel = valueLabel;
            case 2 -> distanceValueLabel = valueLabel;
            default -> {
            }
        }

        card.getChildren().addAll(
                titleLabel,
                valueLabel,
                unitLabel
        );

        return card;
    }

    private LineChart<Number, Number> createChart(
            String title,
            String xLabel,
            String yLabel,
            double yMin,
            double yMax,
            double yTick
    ) {
        NumberAxis xAxis = new NumberAxis();
        xAxis.setLabel(xLabel);
        xAxis.setForceZeroInRange(false);

        NumberAxis yAxis = new NumberAxis(yMin, yMax, yTick);
        yAxis.setLabel(yLabel);

        LineChart<Number, Number> chart =
                new LineChart<>(xAxis, yAxis);

        chart.setTitle(title);
        chart.setAnimated(false);
        chart.setCreateSymbols(false);
        chart.setLegendVisible(false);
        chart.setMinHeight(240);

        return chart;
    }

    // ДОДАВАННЯ ПАЦІЄНТА
    private void addPatientFromHome() {
        String name = patientNameField.getText() == null
                ? ""
                : patientNameField.getText().trim();

        if (name.isEmpty()) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Потрібне ім'я",
                    "Введіть ПІБ пацієнта."
            );
            return;
        }

        if (dbManager.addPatient(name)) {
            patientNameField.clear();

            refreshPatientControls();
            refreshPatientsTable();
            refreshArchivePatientControls();

            selectPatientByName(name);

            showAlert(
                    Alert.AlertType.INFORMATION,
                    "Пацієнта додано",
                    "Пацієнта успішно додано до бази даних."
            );
        } else {
            showAlert(
                    Alert.AlertType.ERROR,
                    "Помилка",
                    "Не вдалося додати пацієнта до бази даних."
            );
        }
    }

    private void selectPatientByName(String name) {
        if (patientComboBox == null) {
            return;
        }

        for (Patient patient : patientComboBox.getItems()) {
            if (patient.fullName.equals(name)) {
                patientComboBox.getSelectionModel().select(patient);
                return;
            }
        }
    }

    // МОНІТОРИНГ
    private void toggleMonitoring() {
        monitoringEnabled = !monitoringEnabled;

        monitoringButton.setText(
                monitoringEnabled
                        ? "Зупинити моніторинг"
                        : "Почати моніторинг"
        );

        if (monitoringEnabled) {
            if (deviceBaseUrl == null) {
                sensorStatusLabel.setText(
                        "Сенсор не знайдено — виконується пошук"
                );

                discoverDeviceAsync(false);
            } else {
                sensorStatusLabel.setText("Моніторинг активний");
            }

            startPolling();
        } else {
            sensorStatusLabel.setText("Моніторинг зупинено");
        }
    }

    private void startPolling() {
        if (!pollingScheduled) {
            pollingScheduled = true;

            pollingExecutor.scheduleAtFixedRate(
                    () -> {
                        if (monitoringEnabled) {
                            pollSensorOnce();
                        } else if (deviceBaseUrl == null) {
                            discoverDeviceAsync(false);
                        }
                    },
                    0,
                    1,
                    TimeUnit.SECONDS
            );
        }
    }

    private void toggleDeviceRecording() {
        PythonBleClient client = bleClient;

        if (client == null || !client.isRunning()) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "BLE не підключено",
                    "Перевірте, чи запущений Python BLE-міст "
                    + "і чи доступний пристрій XIAO-C6."
                );
            return;
        }

        boolean start = !deviceRecording;

        deviceRecordingButton.setDisable(true);

        try {
            boolean sent = client.setRecording(start);

            if (!sent) {
                showAlert(
                    Alert.AlertType.WARNING,
                        "Помилка BLE",
                        "Не вдалося передати команду пристрою."
                );
            }

            recordingStatusLabel.setText(
                start
                        ? "Запис: очікування підтвердження запуску…"
                        : "Запис: очікування підтвердження зупинки…"
            );

        } finally {
            CompletableFuture.delayedExecutor(
                1,
                TimeUnit.SECONDS
                 ).execute(() ->
                    Platform.runLater(() -> {
                        if (deviceRecordingButton != null) {
                         deviceRecordingButton.setDisable(false);
                        }
                    })
             );
            }   
    }

    
private void clearDeviceRecords() {
    String base = deviceBaseUrl;

    if (base == null || base.isBlank()) {
        showAlert(
            Alert.AlertType.WARNING,
            "Пристрій не підключено",
            "Спочатку підключіть MR60BHA2 до програми."
        );
        return;
    }

    // Запитуємо підтвердження перед видаленням.
    Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
    confirmation.setTitle("Видалення запису");
    confirmation.setHeaderText("Видалити попередні вимірювання з пристрою?");
    confirmation.setContentText(
        "Буде очищено файл /records.csv у пам'яті ESP32. " +
        "Цю дію неможливо скасувати."
    );

    Optional<ButtonType> result = confirmation.showAndWait();

    if (result.isEmpty() || result.get() != ButtonType.OK) {
        return;
    }

    CompletableFuture.runAsync(() -> {
        try {
            String response = request(
                base + "/api/records/clear",
                "POST",
                null,
                3000
            );

            Platform.runLater(() -> showAlert(
                Alert.AlertType.INFORMATION,
                "Запис видалено",
                "Попередні вимірювання на пристрої очищено.\n" + response
            ));

        } catch (Exception ex) {
            Platform.runLater(() -> showAlert(
                Alert.AlertType.ERROR,
                "Помилка видалення",
                "Не вдалося видалити запис із пристрою.\n" +
                ex.getMessage()
            ));
        }
    });
}


    private void updateRecordingUi() {
        if (recordingStatusLabel != null) {
            recordingStatusLabel.setText(
                    "Запис на пристрої: " +
                    (deviceRecording ? "активний" : "зупинений")
            );
        }

        if (deviceRecordingButton != null) {
            deviceRecordingButton.setText(
                    deviceRecording
                            ? "Зупинити запис на пристрої"
                            : "Почати запис на пристрої"
            );
        }
    }

    // ВКЛАДКА 2 — БАЗА ДАНИХ
    private VBox createDatabaseView() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        Label patientsTitle = new Label("Пацієнти");
        patientsTitle.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 14px;"
        );

        patientsTable = new TableView<>(patientsList);
        patientsTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY
        );

        TableColumn<Patient, String> idCol =
                new TableColumn<>("ID");

        idCol.setCellValueFactory(c ->
                new SimpleStringProperty(
                        String.valueOf(c.getValue().id)
                )
        );

        TableColumn<Patient, String> nameCol =
                new TableColumn<>("ПІБ пацієнта");

        nameCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().fullName)
        );

        patientsTable.getColumns().addAll(idCol, nameCol);
        patientsTable.setPrefHeight(150);

        Button deletePatientButton =
                new Button("Видалити пацієнта та всі його вимірювання");

        deletePatientButton.setOnAction(
                e -> deleteSelectedPatient()
        );

        Label rawTitle = new Label(
                "Поточні дані сенсора (дослідницька таблиця)"
        );

        rawTitle.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 14px;"
        );

        rawDataTable = new TableView<>(rawDataList);
        rawDataTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY
        );

        TableColumn<RawRecord, String> rawTime =
                new TableColumn<>("Час");

        rawTime.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().getFormattedTime()
                )
        );

        TableColumn<RawRecord, String> rawPulse =
                new TableColumn<>("Пульс (сирий)");

        rawPulse.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().pulseValid
                                ? fmt(c.getValue().rawPulse, 1)
                                : "—"
                )
        );

        TableColumn<RawRecord, String> filteredPulse =
                new TableColumn<>("Пульс (фільтр.)");

        filteredPulse.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().pulseValid
                                ? fmt(c.getValue().filteredPulse, 1)
                                : "—"
                )
        );

        TableColumn<RawRecord, String> rawBreath =
                new TableColumn<>("Дихання (сире)");

        rawBreath.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().breathValid
                                ? fmt(c.getValue().rawBreath, 1)
                                : "—"
                )
        );

        TableColumn<RawRecord, String> filteredBreath =
                new TableColumn<>("Дихання (фільтр.)");

        filteredBreath.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().breathValid
                                ? fmt(c.getValue().filteredBreath, 1)
                                : "—"
                )
        );

        TableColumn<RawRecord, String> rawDistance =
                new TableColumn<>("Відстань, см");

        rawDistance.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().distanceValid
                                ? fmt(c.getValue().rawDistance, 1)
                                : "—"
                )
        );

        rawDataTable.getColumns().addAll(
                rawTime,
                rawPulse,
                filteredPulse,
                rawBreath,
                filteredBreath,
                rawDistance
        );

        VBox.setVgrow(rawDataTable, Priority.ALWAYS);

        Button saveCurrentSeriesButton =
                new Button("Зберегти таблицю вимірювань у БД");

        saveCurrentSeriesButton.setOnAction(
                e -> saveResearchSeries()
        );

        root.getChildren().addAll(
                patientsTitle,
                patientsTable,
                deletePatientButton,
                new Separator(),
                rawTitle,
                rawDataTable,
                saveCurrentSeriesButton
        );

        return root;
    }

    private void deleteSelectedPatient() {
        Patient selected =
                patientsTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Пацієнта не обрано",
                    "Оберіть пацієнта в таблиці."
            );
            return;
        }

        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Видалити пацієнта «" + selected.fullName +
                        "» та всі його вимірювання?",
                ButtonType.YES,
                ButtonType.NO
        );

        confirm.setHeaderText("Підтвердження видалення");

        confirm.showAndWait().ifPresent(answer -> {
            if (answer == ButtonType.YES) {
                if (dbManager.deletePatient(selected.id)) {
                    refreshPatientControls();
                    refreshPatientsTable();
                    refreshArchivePatientControls();
                    refreshArchiveTable();
                } else {
                    showAlert(
                            Alert.AlertType.ERROR,
                            "Помилка",
                            "Не вдалося видалити пацієнта."
                    );
                }
            }
        });
    }

    private void saveResearchSeries() {
        Patient selected = patientComboBox.getValue();

        if (selected == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Пацієнта не обрано",
                    "Оберіть пацієнта на головній сторінці."
            );
            return;
        }

        if (rawDataList.isEmpty()) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Немає вимірювань",
                    "Спочатку отримайте дані від сенсора."
            );
            return;
        }

        if (dbManager.saveRawMeasurements(selected.id, rawDataList)) {
            refreshArchiveTable();

            showAlert(
                    Alert.AlertType.INFORMATION,
                    "Збережено",
                    "Таблицю вимірювань збережено до бази даних."
            );
        } else {
            showAlert(
                    Alert.AlertType.ERROR,
                    "Помилка",
                    "Не вдалося зберегти вимірювання."
            );
        }
    }

    // ВКЛАДКА 3 — АРХІВ
    private VBox createArchiveView() {
        VBox root = new VBox(10);
        root.setPadding(new Insets(15));

        // Заголовок
        HBox heading = new HBox(10);
        heading.setAlignment(Pos.CENTER_LEFT);

        Label title = new Label("Архів вимірювань");
        title.setStyle(
                "-fx-font-weight: bold; -fx-font-size: 16px;"
        );

        archiveCountLabel = new Label("Записів: 0");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        
        Button exportExcelButton = new Button("📊 Завантажити звіт в Excel");
        exportExcelButton.setOnAction(e -> exportArchiveToExcel());


        Button refreshButton = new Button("Показати весь архів");
        refreshButton.setOnAction(e -> {
            archivePatientComboBox.getSelectionModel().clearSelection();
            archiveDateComboBox.getItems().clear();
            refreshArchiveTable();
        });

        heading.getChildren().addAll(
                title,
                spacer,
                archiveCountLabel,
                exportExcelButton,
                refreshButton
        );

        // Рядок пошуку
        HBox searchRow = new HBox(10);
        searchRow.setAlignment(Pos.CENTER_LEFT);

        archivePatientComboBox = new ComboBox<>();
        archivePatientComboBox.setPromptText("Оберіть пацієнта");
        archivePatientComboBox.setPrefWidth(240);

        Button searchPatientButton =
                new Button("Пошук пацієнта");

        searchPatientButton.setOnAction(
                e -> refreshArchiveDates()
        );

        archiveDateComboBox = new ComboBox<>();
        archiveDateComboBox.setPromptText("Дата обстеження");
        archiveDateComboBox.setPrefWidth(180);

        archiveDateComboBox.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);

                if (empty || date == null) {
                    setText(null);
                } else {
                    setText(date.format(DATE_FORMAT));
                }
            }
        });

        archiveDateComboBox.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(LocalDate date, boolean empty) {
                super.updateItem(date, empty);

                if (empty || date == null) {
                    setText("Дата обстеження");
                } else {
                    setText(date.format(DATE_FORMAT));
                }
            }
        });

        Button loadExaminationButton =
                new Button("Завантажити обстеження");

        loadExaminationButton.setOnAction(
                e -> loadSelectedArchive()
        );

        Button clearExaminationButton =
                new Button("Очистити обстеження");

        clearExaminationButton.setOnAction(
                e -> clearSelectedExamination()
        );

        searchRow.getChildren().addAll(
                new Label("Пацієнт:"),
                archivePatientComboBox,
                searchPatientButton,
                new Label("Дата:"),
                archiveDateComboBox,
                loadExaminationButton,
                clearExaminationButton
        );

        // Таблиця архіву
        archiveTable = new TableView<>(archiveList);
        archiveTable.setColumnResizePolicy(
                TableView.CONSTRAINED_RESIZE_POLICY
        );

        TableColumn<ArchiveRecord, String> dateCol =
                new TableColumn<>("Дата і час");

        dateCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().formattedTime)
        );

        TableColumn<ArchiveRecord, String> patientCol =
                new TableColumn<>("Пацієнт");

        patientCol.setCellValueFactory(c ->
                new SimpleStringProperty(c.getValue().patientName)
        );

        TableColumn<ArchiveRecord, String> pulseCol =
                new TableColumn<>("Пульс, уд/хв");

        pulseCol.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().pulseValid
                                ? fmt(c.getValue().filteredPulse, 1)
                                : "—"
                )
        );

        TableColumn<ArchiveRecord, String> breathCol =
                new TableColumn<>("Дихання, вдихів/хв");

        breathCol.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().breathValid
                                ? fmt(c.getValue().filteredBreath, 1)
                                : "—"
                )
        );

        TableColumn<ArchiveRecord, String> distanceCol =
                new TableColumn<>("Відстань, см");

        distanceCol.setCellValueFactory(c ->
                new SimpleStringProperty(
                        c.getValue().distanceValid
                                ? fmt(c.getValue().rawDistance, 1)
                                : "—"
                )
        );

        archiveTable.getColumns().addAll(
                dateCol,
                patientCol,
                pulseCol,
                breathCol,
                distanceCol
        );

        VBox.setVgrow(archiveTable, Priority.ALWAYS);

        Button deleteRecordButton =
                new Button("Видалити вибраний запис");

        deleteRecordButton.setOnAction(
                e -> deleteSelectedArchiveRecord()
        );

        root.getChildren().addAll(
                heading,
                searchRow,
                archiveTable,
                deleteRecordButton
        );

        return root;
    }

    // Оновлює список пацієнтів у полі пошуку архіву
    private void refreshArchivePatientControls() {
        if (archivePatientComboBox == null) {
            return;
        }

        Patient previous = archivePatientComboBox.getValue();

        archivePatientComboBox.setItems(
                FXCollections.observableArrayList(
                        dbManager.getAllPatients()
                )
        );

        if (previous != null) {
            for (Patient patient : archivePatientComboBox.getItems()) {
                if (patient.id == previous.id) {
                    archivePatientComboBox.getSelectionModel().select(patient);
                    break;
                }
            }
        }
    }

    // Крок 1: вибрати пацієнта і знайти дати його обстежень
    private void refreshArchiveDates() {
        Patient patient = archivePatientComboBox.getValue();

        archiveDateComboBox.getItems().clear();
        archiveList.clear();

        if (patient == null) {
            updateArchiveCount();

            showAlert(
                    Alert.AlertType.WARNING,
                    "Пацієнта не обрано",
                    "Спочатку оберіть пацієнта."
            );
            return;
        }

        List<LocalDate> dates =
                dbManager.getMeasurementDates(patient.id);

        archiveDateComboBox.getItems().setAll(dates);

        if (dates.isEmpty()) {
            updateArchiveCount();

            showAlert(
                    Alert.AlertType.INFORMATION,
                    "Обстежень немає",
                    "Для цього пацієнта в архіві немає збережених обстежень."
            );
            return;
        }

        archiveDateComboBox.getSelectionModel().selectFirst();
        updateArchiveCount();
    }

    // Крок 2: завантажити вимірювання конкретного пацієнта за конкретну дату
    private void loadSelectedArchive() {
        Patient patient = archivePatientComboBox.getValue();
        LocalDate date = archiveDateComboBox.getValue();

        if (patient == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Пацієнта не обрано",
                    "Спочатку оберіть пацієнта та натисніть «Пошук пацієнта»."
            );
            return;
        }

        if (date == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Дату не обрано",
                    "Спочатку знайдіть дати обстежень і виберіть дату."
            );
            return;
        }

        List<ArchiveRecord> records =
                dbManager.getMeasurementsForPatientAndDate(
                        patient.id,
                        date
                );

        archiveList.setAll(records);
        updateArchiveCount();

        if (records.isEmpty()) {
            showAlert(
                    Alert.AlertType.INFORMATION,
                    "Даних немає",
                    "За вибрану дату вимірювань не знайдено."
            );
        }
    }

    // Очищує тільки вимірювання вибраного пацієнта за вибрану дату
    private void clearSelectedExamination() {
        Patient patient = archivePatientComboBox.getValue();
        LocalDate date = archiveDateComboBox.getValue();

        if (patient == null || date == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Обстеження не обрано",
                    "Оберіть пацієнта, знайдіть дати та виберіть дату обстеження."
            );
            return;
        }

        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Видалити всі вимірювання пацієнта «" +
                        patient.fullName + "» за " +
                        date.format(DATE_FORMAT) + "?\n" +
                        "Інші дати та пацієнти залишаться в архіві.",
                ButtonType.YES,
                ButtonType.NO
        );

        confirm.setHeaderText("Підтвердження видалення");

        confirm.showAndWait().ifPresent(answer -> {
            if (answer == ButtonType.YES) {
                boolean success =
                        dbManager.deleteMeasurementsForPatientAndDate(
                                patient.id,
                                date
                        );

                if (success) {
                    refreshArchiveDates();

                    showAlert(
                            Alert.AlertType.INFORMATION,
                            "Обстеження очищено",
                            "Вимірювання за вибрану дату видалено."
                    );
                } else {
                    showAlert(
                            Alert.AlertType.ERROR,
                            "Помилка",
                            "Не вдалося очистити вибране обстеження."
                    );
                }
            }
        });
    }

    private void deleteSelectedArchiveRecord() {
        ArchiveRecord selected =
                archiveTable.getSelectionModel().getSelectedItem();

        if (selected == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Запис не обрано",
                    "Оберіть запис в архіві."
            );
            return;
        }

        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                "Видалити вибраний запис архіву?",
                ButtonType.YES,
                ButtonType.NO
        );

        confirm.showAndWait().ifPresent(answer -> {
            if (answer == ButtonType.YES) {
                if (dbManager.deleteMeasurement(selected.id)) {
                    Patient patient = archivePatientComboBox.getValue();
                    LocalDate date = archiveDateComboBox.getValue();

                    if (patient != null && date != null) {
                        loadSelectedArchive();
                    } else {
                        refreshArchiveTable();
                    }
                } else {
                    showAlert(
                            Alert.AlertType.ERROR,
                            "Помилка",
                            "Не вдалося видалити запис."
                    );
                }
            }
        });
    }

    private void archiveCurrentData() {
        Patient patient = patientComboBox.getValue();

        if (patient == null) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Пацієнта не обрано",
                    "Оберіть або додайте пацієнта перед внесенням даних до архіву."
            );

            tabPane.getSelectionModel().select(1);
            return;
        }

        SensorSnapshot snapshot = latestSnapshot;
        long receivedAt = latestSnapshotReceivedAt;

        if (snapshot == null ||
                System.currentTimeMillis() - receivedAt > 5000) {
            showAlert(
                    Alert.AlertType.WARNING,
                    "Немає актуальних даних",
                    "Немає свіжих даних від сенсора. Перевірте підключення та запустіть моніторинг."
            );
            return;
        }
        if (monitoringEnabled) {
            if (snapshot.heartValid) {
                addChartPoint(pulseSeries, snapshot.filteredPulse);
            }
            if (snapshot.breathValid) {
                addChartPoint(breathSeries, snapshot.filteredBreath);
            }
            chartTimeIndex++;
        }
        RawRecord record = new RawRecord(
                System.currentTimeMillis(),
                snapshot.heart,
                snapshot.filteredPulse,
                snapshot.breath,
                snapshot.filteredBreath,
                snapshot.distance,
                snapshot.heartValid,
                snapshot.breathValid,
                snapshot.distanceValid
        );

        if (dbManager.saveOneMeasurement(patient.id, record)) {
            refreshArchiveTable();

            showAlert(
                    Alert.AlertType.INFORMATION,
                    "Архів оновлено",
                    "Поточні дані збережено для пацієнта: " +
                            patient.fullName
            );
        } else {
            showAlert(
                    Alert.AlertType.ERROR,
                    "Помилка архівації",
                    "Не вдалося зберегти поточні дані до бази даних."
            );
        }
    }

    // ДАНІ СЕНСОРА ТА ГРАФІКИ
    
    private void acceptSensorSnapshot(SensorSnapshot snapshot) {
        boolean heartOk =
                snapshot.heartValid && snapshot.heart > 0;

        boolean breathOk =
                snapshot.breathValid && snapshot.breath > 0;

        boolean distanceOk =
                snapshot.distanceValid
                        && snapshot.distance > 0
                        && snapshot.distance <= 600;

        ProcessResult result = mathProcessor.process(
                heartOk ? snapshot.heart : 0,
                heartOk,
                breathOk ? snapshot.breath : 0,
                breathOk,
                distanceOk ? snapshot.distance : 0,
                distanceOk
        );

        snapshot.filteredPulse = result.live.pulse;
        snapshot.filteredBreath = result.live.breath;

        snapshot.heartValid = result.live.pulseValid && heartOk;
        snapshot.breathValid = result.live.breathValid && breathOk;
        snapshot.distanceValid = result.live.distanceValid && distanceOk;

        latestSnapshot = snapshot;
        latestSnapshotReceivedAt = System.currentTimeMillis();

        RawRecord record = new RawRecord(
                latestSnapshotReceivedAt,
                snapshot.heart,
                snapshot.filteredPulse,
                snapshot.breath,
                snapshot.filteredBreath,
                snapshot.distance,
                snapshot.heartValid,
                snapshot.breathValid,
                snapshot.distanceValid
        );

        Platform.runLater(() -> {
            // Показники та дослідницька таблиця оновлюються
            // незалежно від стану запису.
            rawDataList.add(record);

            if (rawDataList.size() > 1000) {
                rawDataList.remove(0);
            }

            heartValueLabel.setText(
                    snapshot.heartValid
                            ? fmt(snapshot.filteredPulse, 0)
                            : "—"
            );

            breathValueLabel.setText(
                    snapshot.breathValid
                            ? fmt(snapshot.filteredBreath, 0)
                            : "—"
            );

            distanceValueLabel.setText(
                    snapshot.distanceValid
                            ? fmt(snapshot.distance, 1)
                            : "—"
            );

            sensorStatusLabel.setText(
                    snapshot.target
                            ? "Ціль виявлена"
                            : "Ціль не визначена"
            );

            // Стан беремо з телеметрії пристрою,
            // а не просто з натискання кнопки.
            deviceRecording = snapshot.recording;
            updateRecordingUi();

            // Графіки оновлюються лише під час запису.
            if (deviceRecording) {
                if (snapshot.heartValid) {
                    addChartPoint(pulseSeries, snapshot.filteredPulse);
                }

                if (snapshot.breathValid) {
                    addChartPoint(breathSeries, snapshot.filteredBreath);
                }

                chartTimeIndex++;
            }
        });
    }


    public void onNewSensorData(
            double rawPulse,
            double rawBreath,
            double rawDistance
    ) {
        SensorSnapshot snapshot = new SensorSnapshot();

        snapshot.heart = rawPulse;
        snapshot.breath = rawBreath;
        snapshot.distance = rawDistance;

        snapshot.heartValid =
                Double.isFinite(rawPulse) && rawPulse > 0;

        snapshot.breathValid =
                Double.isFinite(rawBreath) && rawBreath > 0;

        snapshot.distanceValid =
                Double.isFinite(rawDistance) &&
                rawDistance > 0 &&
                rawDistance <= 600;

        snapshot.target = snapshot.distanceValid;

        acceptSensorSnapshot(snapshot);
    }

    private void addChartPoint(
            XYChart.Series<Number, Number> series,
            double value
    ) {
        if (series.getData().size() >= MAX_CHART_POINTS) {
            series.getData().remove(0);
        }

        series.getData().add(
                new XYChart.Data<>(chartTimeIndex, value)
        );
    }

    // ПОШУК ESP32-C6 / MR60BHA2
    private void discoverDeviceAsync(boolean userRequested) {
        if (discoveryRunning) {
            return;
        }

        discoveryRunning = true;

        Platform.runLater(() -> {
            if (discoverButton != null) {
                discoverButton.setDisable(true);
            }

            if (connectionStatusLabel != null) {
                connectionStatusLabel.setText("Сенсор: пошук…");
            }
        });

        CompletableFuture.runAsync(() -> {
            try {
                String found = findDevice();

                if (found != null) {
                    deviceBaseUrl = found;

                    request(
                            found + "/api/device",
                            "GET",
                            null,
                            1500
                    );

                    String sensorJson = request(
                            found + "/api/sensor",
                            "GET",
                            null,
                            1500
                    );

                    SensorSnapshot snapshot =
                            parseSensorJson(sensorJson);

                    deviceRecording = snapshot.recording;
                    acceptSensorSnapshot(snapshot);

                    Platform.runLater(() -> {
                        connectionStatusLabel.setText(
                                "Сенсор підключено: " + found
                        );

                        sensorStatusLabel.setText(
                                "Отримано дані від MR60BHA2"
                        );

                        updateRecordingUi();
                    });

                    if (monitoringEnabled) {
                        pollSensorOnce();
                    }
                } else {
                    Platform.runLater(() -> {
                        connectionStatusLabel.setText(
                                "Сенсор не знайдено"
                        );

                        sensorStatusLabel.setText(
                                "Перевірте Wi-Fi або підключення до точки MR60BHA2-XXXX"
                        );
                    });
                }

            } catch (Exception ex) {
                Platform.runLater(() ->
                        connectionStatusLabel.setText(
                                "Помилка зв'язку із сенсором"
                        )
                );
            } finally {
                discoveryRunning = false;

                Platform.runLater(() -> {
                    if (discoverButton != null) {
                        discoverButton.setDisable(false);
                    }
                });
            }
        }, discoveryExecutor);
    }

    private String findDevice() {
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        candidates.add("http://" + DEFAULT_AP_IP);

        try {
            Enumeration<NetworkInterface> interfaces =
                    NetworkInterface.getNetworkInterfaces();

            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();

                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }

                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress address = ia.getAddress();

                    if (!(address instanceof Inet4Address) ||
                            address.isLoopbackAddress()) {
                        continue;
                    }

                    int prefix = ia.getNetworkPrefixLength();

                    if (prefix < 1 || prefix > 30) {
                        continue;
                    }

                    int scanPrefix = Math.max(prefix, 24);

                    long ip = ipv4ToLong((Inet4Address) address);

                    long mask =
                            (0xFFFFFFFFL << (32 - scanPrefix))
                                    & 0xFFFFFFFFL;

                    long network = ip & mask;
                    long broadcast =
                            network | (~mask & 0xFFFFFFFFL);

                    for (long host = network + 1;
                         host < broadcast;
                         host++) {

                        if (host == ip) {
                            continue;
                        }

                        candidates.add(
                                "http://" + longToIpv4(host)
                        );
                    }
                }
            }
        } catch (Exception ignored) {
            // IP точки доступу все одно буде перевірено
        }

        List<Callable<String>> tasks = new ArrayList<>();

        for (String candidate : candidates) {
            tasks.add(() ->
                    isOurDevice(candidate) ? candidate : null
            );
        }

        try {
            List<Future<String>> futures =
                    discoveryExecutor.invokeAll(
                            tasks,
                            6,
                            TimeUnit.SECONDS
                    );

            for (Future<String> future : futures) {
                if (future.isCancelled()) {
                    continue;
                }

                try {
                    String result = future.get();

                    if (result != null) {
                        return result;
                    }
                } catch (ExecutionException ignored) {
                    // Перевіряємо наступну адресу
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return null;
    }

    private boolean isOurDevice(String baseUrl) {
        try {
            String json = request(
                    baseUrl + "/api/device",
                    "GET",
                    null,
                    HTTP_TIMEOUT_MS
            );

            return json.contains("\"ble_name\":\"XIAO-C6\"")
                    && json.contains("\"name\":\"MR60BHA2\"");
        } catch (Exception ignored) {
            return false;
        }
    }

    private void pollSensorOnce() {
        String base = deviceBaseUrl;

        if (base == null || !monitoringEnabled) {
            return;
        }

        if (bleTelemetryActive) {
            return;
        }

        try {
            String sensorJson = request(
                    base + "/api/sensor",
                    "GET",
                    null,
                    1400
            );

            SensorSnapshot snapshot =
                    parseSensorJson(sensorJson);

            acceptSensorSnapshot(snapshot);

            Platform.runLater(() ->
                    connectionStatusLabel.setText(
                            "Сенсор підключено: " + base
                    )
            );

        } catch (Exception ex) {
            Platform.runLater(() -> {
                connectionStatusLabel.setText(
                        "Немає зв'язку — повторна спроба"
                );

                sensorStatusLabel.setText(
                        "Очікування відновлення підключення"
                );
            });

            deviceBaseUrl = null;
        }
    }

    // HTTP ТА JSON
    private static String request(
            String urlString,
            String method,
            String body,
            int timeoutMs
    ) throws IOException {

        HttpURLConnection connection =
                (HttpURLConnection) new URL(urlString).openConnection();

        connection.setRequestMethod(method);
        connection.setConnectTimeout(timeoutMs);
        connection.setReadTimeout(timeoutMs);
        connection.setUseCaches(false);
        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        if (body != null) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);

            connection.setDoOutput(true);

            connection.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded; charset=UTF-8"
            );

            connection.setFixedLengthStreamingMode(bytes.length);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
        }

        try {
            int status = connection.getResponseCode();

            InputStream stream =
                    status >= 200 && status < 400
                            ? connection.getInputStream()
                            : connection.getErrorStream();

            String response = "";

            if (stream != null) {
                try (
                        InputStream in = stream;
                        ByteArrayOutputStream out =
                                new ByteArrayOutputStream()
                ) {
                    byte[] buffer = new byte[1024];
                    int count;

                    while ((count = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, count);
                    }

                    response = out.toString(
                            StandardCharsets.UTF_8
                    );
                }
            }

            if (status < 200 || status >= 400) {
                throw new IOException(
                        "HTTP " + status +
                        (response.isBlank() ? "" : ": " + response)
                );
            }

            return response;

        } finally {
            connection.disconnect();
        }
    }

    private static SensorSnapshot parseSensorJson(String json) {
        SensorSnapshot snapshot = new SensorSnapshot();

        snapshot.heart = jsonDouble(json, "heart", 0);
        snapshot.breath = jsonDouble(json, "breath", 0);
        snapshot.distance = jsonDouble(json, "distance", 0);

        snapshot.heartValid =
                jsonBoolean(json, "heart_valid", false);

        snapshot.breathValid =
                jsonBoolean(json, "breath_valid", false);

        snapshot.distanceValid =
                jsonBoolean(json, "distance_valid", false);

        snapshot.target =
                jsonBoolean(json, "target", false);

        snapshot.recording =
                jsonBoolean(json, "recording", false);

        snapshot.wifiConnected =
                jsonBoolean(json, "wifi_connected", false);

        snapshot.bleConnected =
                jsonBoolean(json, "ble_connected", false);

        return snapshot;
    }

    private static double jsonDouble(
            String json,
            String key,
            double fallback
    ) {
        Matcher matcher = Pattern.compile(
                "\"" + Pattern.quote(key) +
                "\"\\s*:\\s*(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+)(?:[eE][+-]?\\d+)?)"
        ).matcher(json);

        if (!matcher.find()) {
            return fallback;
        }

        try {
            double value = Double.parseDouble(matcher.group(1));

            return Double.isFinite(value) ? value : fallback;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static boolean jsonBoolean(
            String json,
            String key,
            boolean fallback
    ) {
        Matcher matcher = Pattern.compile(
                "\"" + Pattern.quote(key) +
                "\"\\s*:\\s*(true|false)",
                Pattern.CASE_INSENSITIVE
        ).matcher(json);

        return matcher.find()
                ? Boolean.parseBoolean(matcher.group(1))
                : fallback;
    }

    private static long ipv4ToLong(Inet4Address address) {
        byte[] bytes = address.getAddress();

        return ((bytes[0] & 0xFFL) << 24)
                | ((bytes[1] & 0xFFL) << 16)
                | ((bytes[2] & 0xFFL) << 8)
                | (bytes[3] & 0xFFL);
    }

    private static String longToIpv4(long ip) {
        return ((ip >>> 24) & 255)
                + "." + ((ip >>> 16) & 255)
                + "." + ((ip >>> 8) & 255)
                + "." + (ip & 255);
    }

    // ЗАГАЛЬНІ ДОПОМІЖНІ МЕТОДИ
    private void refreshPatientControls() {
        if (patientComboBox == null) {
            return;
        }

        Patient selected = patientComboBox.getValue();

        patientComboBox.setItems(
                FXCollections.observableArrayList(
                        dbManager.getAllPatients()
                )
        );

        if (selected != null) {
            for (Patient patient : patientComboBox.getItems()) {
                if (patient.id == selected.id) {
                    patientComboBox.getSelectionModel().select(patient);
                    break;
                }
            }
        }
    }

    private void refreshPatientsTable() {
        if (patientsTable == null) {
            return;
        }

        patientsList.setAll(dbManager.getAllPatients());
    }

    private void refreshArchiveTable() {
        if (archiveTable == null) {
            return;
        }

        archiveList.setAll(dbManager.getAllMeasurements());
        updateArchiveCount();
    }

    private void exportArchiveToExcel() {
    // Беремо пацієнта та дату з фільтрів вкладки «Архів»
    Patient selectedPatient = archivePatientComboBox.getValue();
    LocalDate selectedDate = archiveDateComboBox.getValue();

    if (selectedPatient == null) {
        showAlert(
                Alert.AlertType.WARNING,
                "Пацієнта не обрано",
                "Спочатку виберіть пацієнта у фільтрі вкладки «Архів»."
        );
        return;
    }

    if (selectedDate == null) {
        showAlert(
                Alert.AlertType.WARNING,
                "Дату не обрано",
                "Виберіть пацієнта, натисніть «Пошук пацієнта» " +
                "та виберіть дату обстеження."
        );
        return;
    }

    // Завантажуємо лише записи вибраного пацієнта за вибрану дату
    List<ArchiveRecord> records =
            dbManager.getMeasurementsForPatientAndDate(
                    selectedPatient.id,
                    selectedDate
            );

    if (records.isEmpty()) {
        showAlert(
                Alert.AlertType.WARNING,
                "Немає даних",
                "Для пацієнта «" + selectedPatient.fullName +
                "» за " + selectedDate.format(DATE_FORMAT) +
                " немає вимірювань для експорту."
        );
        return;
    }

    FileChooser chooser = new FileChooser();
    chooser.setTitle("Експорт обстеження в Excel");

    chooser.getExtensionFilters().add(
            new FileChooser.ExtensionFilter(
                    "Книга Excel (*.xlsx)",
                    "*.xlsx"
            )
    );

    chooser.setInitialFileName(
            "PulsBreath_" +
            selectedPatient.fullName.replaceAll("[\\\\/:*?\"<>|]", "_") +
            "_" + selectedDate + ".xlsx"
    );

    File file = chooser.showSaveDialog(primaryStage);

    if (file == null) {
        return;
    }

    if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
        file = new File(
                file.getParentFile(),
                file.getName() + ".xlsx"
        );
    }

    String[] headers = {
            "Дата та час",
            "Пацієнт",
            "Пульс, уд/хв",
            "Частота дихання, вдихів/хв",
            "Відстань, см",
            "Підозра на патологію пульсу",
            "Підозра на патологію дихання"
    };

    try (Workbook workbook = new XSSFWorkbook()) {
        Sheet sheet = workbook.createSheet("Медичний витяг");

        // Стиль заголовків.
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());

        CellStyle headerStyle = workbook.createCellStyle();
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(
                IndexedColors.DARK_BLUE.getIndex()
        );
        headerStyle.setFillPattern(
                FillPatternType.SOLID_FOREGROUND
        );
        headerStyle.setWrapText(true);
        headerStyle.setVerticalAlignment(
                VerticalAlignment.CENTER
        );

        Row headerRow = sheet.createRow(0);
        headerRow.setHeightInPoints(36);

        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell =
                    headerRow.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        // Стилі результатів
        Font warningFont = workbook.createFont();
        warningFont.setColor(IndexedColors.DARK_RED.getIndex());

        CellStyle warningStyle = workbook.createCellStyle();
        warningStyle.setFont(warningFont);
        warningStyle.setWrapText(true);

        Font normalFont = workbook.createFont();
        normalFont.setColor(IndexedColors.DARK_GREEN.getIndex());

        CellStyle normalStyle = workbook.createCellStyle();
        normalStyle.setFont(normalFont);
        normalStyle.setWrapText(true);

        int rowIndex = 1;

        for (ArchiveRecord record : records) {
            Row row = sheet.createRow(rowIndex++);

            // Дата та час
            row.createCell(0).setCellValue(record.formattedTime);

            // ПІБ вибраного пацієнта
            row.createCell(1).setCellValue(
                    record.patientName == null
                            ? selectedPatient.fullName
                            : record.patientName
            );

            // Пульс
            if (record.pulseValid
                    && Double.isFinite(record.filteredPulse)
                    && record.filteredPulse > 0) {
                row.createCell(2).setCellValue(record.filteredPulse);
            } else {
                row.createCell(2).setCellValue("—");
            }

            // Частота дихання
            if (record.breathValid
                    && Double.isFinite(record.filteredBreath)
                    && record.filteredBreath > 0) {
                row.createCell(3).setCellValue(record.filteredBreath);
            } else {
                row.createCell(3).setCellValue("—");
            }

            // Відстань
            if (record.distanceValid
                    && Double.isFinite(record.rawDistance)
                    && record.rawDistance > 0) {
                row.createCell(4).setCellValue(record.rawDistance);
            } else {
                row.createCell(4).setCellValue("—");
            }

            // Класифікація пульсу
            String pulseStatus;

            if (!record.pulseValid
                    || !Double.isFinite(record.filteredPulse)
                    || record.filteredPulse <= 0) {
                pulseStatus = "Недостатньо даних";
            } else {
                pulseStatus = MathProcessor.classifyPulse(
                        record.filteredPulse
                );
            }

            String pulseSuspicion;

            if ("Недостатньо даних".equals(pulseStatus)) {
                pulseSuspicion = "Недостатньо даних";
            } else if ("Норма".equals(pulseStatus)) {
                pulseSuspicion = "За пороговою оцінкою не виявлено";
            } else {
                pulseSuspicion = "Потрібна увага: " + pulseStatus;
            }

            org.apache.poi.ss.usermodel.Cell pulseCell =
                    row.createCell(5);
            pulseCell.setCellValue(pulseSuspicion);

            if ("Норма".equals(pulseStatus)) {
                pulseCell.setCellStyle(normalStyle);
            } else if (!"Недостатньо даних".equals(pulseStatus)) {
                pulseCell.setCellStyle(warningStyle);
            }

            // Класифікація дихання
            String breathStatus;

            if (!record.breathValid
                    || !Double.isFinite(record.filteredBreath)
                    || record.filteredBreath <= 0) {
                breathStatus = "Недостатньо даних";
            } else {
                breathStatus = MathProcessor.classifyBreath(
                        record.filteredBreath
                );
            }

            String breathSuspicion;

            if ("Недостатньо даних".equals(breathStatus)) {
                breathSuspicion = "Недостатньо даних";
            } else if ("Еупное".equals(breathStatus)) {
                breathSuspicion = "За пороговою оцінкою не виявлено";
            } else {
                breathSuspicion = "Потрібна увага: " + breathStatus;
            }

            org.apache.poi.ss.usermodel.Cell breathCell =
                    row.createCell(6);
            breathCell.setCellValue(breathSuspicion);

            if ("Еупное".equals(breathStatus)) {
                breathCell.setCellStyle(normalStyle);
            } else if (!"Недостатньо даних".equals(breathStatus)) {
                breathCell.setCellStyle(warningStyle);
            }
        }

        // Закріплення заголовка та фільтри
        sheet.createFreezePane(0, 1);
        sheet.setAutoFilter(
                new CellRangeAddress(
                        0,
                        records.size(),
                        0,
                        headers.length - 1
                )
        );

        // Ширина стовпців
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }

        sheet.setColumnWidth(5, 32 * 256);
        sheet.setColumnWidth(6, 32 * 256);

        try (OutputStream output = new FileOutputStream(file)) {
            workbook.write(output);
        }

        showAlert(
                Alert.AlertType.INFORMATION,
                "Експорт завершено",
                "Excel-файл містить лише обстеження вибраного пацієнта.\n\n" +
                "Пацієнт: " + selectedPatient.fullName + "\n" +
                "Дата: " + selectedDate.format(DATE_FORMAT) + "\n" +
                "Кількість записів: " + records.size() + "\n\n" +
                "Файл збережено:\n" + file.getAbsolutePath()
        );

    } catch (IOException ex) {
        ex.printStackTrace();

        showAlert(
                Alert.AlertType.ERROR,
                "Помилка експорту",
                "Не вдалося зберегти Excel-файл:\n" +
                ex.getMessage()
        );
    }
}

    private void updateArchiveCount() {
        if (archiveCountLabel != null) {
            archiveCountLabel.setText(
                    "Записів: " + archiveList.size()
            );
        }
    }

    private static String fmt(double value, int decimals) {
        return String.format(
                Locale.US,
                "%." + decimals + "f",
                value
        );
    }

    private void showAlert(
            Alert.AlertType type,
            String title,
            String message
    ) {
        Alert alert = new Alert(type);

        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);

        alert.showAndWait();
    }

    @Override
    public void stop() {
        pollingExecutor.shutdownNow();
        discoveryExecutor.shutdownNow();

        if (bleClient != null) {
            bleClient.stop();
        }
    }

    // МОДЕЛІ
    public static class Patient {
        public final int id;
        public final String fullName;

        public Patient(int id, String fullName) {
            this.id = id;
            this.fullName = fullName;
        }

        @Override
        public String toString() {
            return fullName;
        }
    }

    private static class SensorSnapshot {
        double heart;
        double breath;
        double distance;

        double filteredPulse;
        double filteredBreath;

        boolean heartValid;
        boolean breathValid;
        boolean distanceValid;

        boolean target;
        boolean recording;
        boolean wifiConnected;
        boolean bleConnected;
    }

    public static class RawRecord {
        public final long timestamp;

        public final double rawPulse;
        public final double filteredPulse;

        public final double rawBreath;
        public final double filteredBreath;

        public final double rawDistance;

        public final boolean pulseValid;
        public final boolean breathValid;
        public final boolean distanceValid;

        public RawRecord(
                long timestamp,
                double rawPulse,
                double filteredPulse,
                double rawBreath,
                double filteredBreath,
                double rawDistance
        ) {
            this(
                    timestamp,
                    rawPulse,
                    filteredPulse,
                    rawBreath,
                    filteredBreath,
                    rawDistance,
                    rawPulse > 0,
                    rawBreath > 0,
                    rawDistance > 0
            );
        }

        public RawRecord(
                long timestamp,
                double rawPulse,
                double filteredPulse,
                double rawBreath,
                double filteredBreath,
                double rawDistance,
                boolean pulseValid,
                boolean breathValid,
                boolean distanceValid
        ) {
            this.timestamp = timestamp;
            this.rawPulse = rawPulse;
            this.filteredPulse = filteredPulse;
            this.rawBreath = rawBreath;
            this.filteredBreath = filteredBreath;
            this.rawDistance = rawDistance;
            this.pulseValid = pulseValid;
            this.breathValid = breathValid;
            this.distanceValid = distanceValid;
        }

        public String getFormattedTime() {
            return TIME_FORMAT.format(
                    Instant.ofEpochMilli(timestamp)
            );
        }
    }

    public static class ArchiveRecord {
        public final int id;
        public final String patientName;
        public final long timestamp;

        public final double filteredPulse;
        public final double filteredBreath;
        public final double rawDistance;

        public final boolean pulseValid;
        public final boolean breathValid;
        public final boolean distanceValid;

        public final String formattedTime;

        public ArchiveRecord(
                int id,
                String patientName,
                long timestamp,
                double filteredPulse,
                double filteredBreath,
                double rawDistance,
                boolean pulseValid,
                boolean breathValid,
                boolean distanceValid
        ) {
            this.id = id;
            this.patientName = patientName;
            this.timestamp = timestamp;
            this.filteredPulse = filteredPulse;
            this.filteredBreath = filteredBreath;
            this.rawDistance = rawDistance;
            this.pulseValid = pulseValid;
            this.breathValid = breathValid;
            this.distanceValid = distanceValid;

            this.formattedTime = TIME_FORMAT.format(
                    Instant.ofEpochMilli(timestamp)
            );
        }
    }

    // SQLITE — БАЗА ДАНИХ
    public static class DatabaseManager {

        private static final String URL =
                "jdbc:sqlite:pulsbreath.db";

        public void initDatabase() {
            try (
                    Connection conn = DriverManager.getConnection(URL);
                    Statement stmt = conn.createStatement()
            ) {
                stmt.execute("PRAGMA foreign_keys = ON");

                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS patients (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "full_name TEXT NOT NULL)"
                );

                stmt.execute(
                        "CREATE TABLE IF NOT EXISTS measurements (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "patient_id INTEGER, " +
                        "timestamp INTEGER, " +
                        "raw_pulse REAL, " +
                        "filtered_pulse REAL, " +
                        "raw_breath REAL, " +
                        "filtered_breath REAL, " +
                        "raw_distance REAL, " +
                        "pulse_valid INTEGER NOT NULL DEFAULT 1, " +
                        "breath_valid INTEGER NOT NULL DEFAULT 1, " +
                        "distance_valid INTEGER NOT NULL DEFAULT 1, " +
                        "FOREIGN KEY(patient_id) REFERENCES patients(id))"
                );

                ensureColumn(
                        conn,
                        "measurements",
                        "pulse_valid",
                        "INTEGER NOT NULL DEFAULT 1"
                );

                ensureColumn(
                        conn,
                        "measurements",
                        "breath_valid",
                        "INTEGER NOT NULL DEFAULT 1"
                );

                ensureColumn(
                        conn,
                        "measurements",
                        "distance_valid",
                        "INTEGER NOT NULL DEFAULT 1"
                );

            } catch (SQLException e) {
                e.printStackTrace();
            }
        }

        private static void ensureColumn(
                Connection conn,
                String table,
                String column,
                String definition
        ) throws SQLException {

            boolean exists = false;

            try (
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(
                            "PRAGMA table_info(" + table + ")"
                    )
            ) {
                while (rs.next()) {
                    if (column.equalsIgnoreCase(
                            rs.getString("name")
                    )) {
                        exists = true;
                        break;
                    }
                }
            }

            if (!exists) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute(
                            "ALTER TABLE " + table +
                            " ADD COLUMN " + column +
                            " " + definition
                    );
                }
            }
        }

        // Додає пацієнта з найменшим вільним додатним ID
        public boolean addPatient(String fullName) {
            if (fullName == null || fullName.trim().isEmpty()) {
                return false;
            }

            String sql =
                    "INSERT INTO patients(id, full_name) VALUES(?, ?)";

            try (Connection conn = DriverManager.getConnection(URL)) {
                conn.setAutoCommit(false);

                try {
                    int nextId = 1;

                    try (
                            Statement stmt = conn.createStatement();
                            ResultSet rs = stmt.executeQuery(
                                    "SELECT id FROM patients " +
                                    "WHERE id > 0 ORDER BY id"
                            )
                    ) {
                        while (rs.next()) {
                            int existingId = rs.getInt("id");

                            if (existingId < nextId) {
                                continue;
                            }

                            if (existingId == nextId) {
                                nextId++;
                            } else {
                                break;
                            }
                        }
                    }

                    try (PreparedStatement ps =
                                 conn.prepareStatement(sql)) {
                        ps.setInt(1, nextId);
                        ps.setString(2, fullName.trim());
                        ps.executeUpdate();
                    }

                    conn.commit();
                    return true;

                } catch (SQLException e) {
                    conn.rollback();
                    e.printStackTrace();
                    return false;
                }

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        public List<Patient> getAllPatients() {
            List<Patient> list = new ArrayList<>();

            String sql =
                    "SELECT id, full_name FROM patients " +
                    "ORDER BY full_name COLLATE NOCASE";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)
            ) {
                while (rs.next()) {
                    list.add(
                            new Patient(
                                    rs.getInt("id"),
                                    rs.getString("full_name")
                            )
                    );
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            return list;
        }

        public boolean deletePatient(int patientId) {
            try (Connection conn = DriverManager.getConnection(URL)) {
                conn.setAutoCommit(false);

                try (
                        PreparedStatement deleteMeasurements =
                                conn.prepareStatement(
                                        "DELETE FROM measurements " +
                                        "WHERE patient_id = ?"
                                );
                        PreparedStatement deletePatient =
                                conn.prepareStatement(
                                        "DELETE FROM patients WHERE id = ?"
                                )
                ) {
                    deleteMeasurements.setInt(1, patientId);
                    deleteMeasurements.executeUpdate();

                    deletePatient.setInt(1, patientId);

                    int deleted = deletePatient.executeUpdate();

                    if (deleted == 0) {
                        conn.rollback();
                        return false;
                    }

                    conn.commit();
                    return true;

                } catch (SQLException e) {
                    conn.rollback();
                    e.printStackTrace();
                    return false;
                }

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        public boolean saveRawMeasurements(
                int patientId,
                ObservableList<RawRecord> records
        ) {
            String sql =
                    "INSERT INTO measurements(" +
                    "patient_id,timestamp,raw_pulse,filtered_pulse," +
                    "raw_breath,filtered_breath,raw_distance," +
                    "pulse_valid,breath_valid,distance_valid" +
                    ") VALUES(?,?,?,?,?,?,?,?,?,?)";

            try (Connection conn = DriverManager.getConnection(URL)) {
                conn.setAutoCommit(false);

                try (PreparedStatement ps =
                             conn.prepareStatement(sql)) {

                    for (RawRecord record : records) {
                        bindRecord(ps, patientId, record);
                    }

                    ps.executeBatch();
                    conn.commit();
                    return true;

                } catch (SQLException e) {
                    conn.rollback();
                    e.printStackTrace();
                    return false;
                }

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        public boolean saveOneMeasurement(
                int patientId,
                RawRecord record
        ) {
            String sql =
                    "INSERT INTO measurements(" +
                    "patient_id,timestamp,raw_pulse,filtered_pulse," +
                    "raw_breath,filtered_breath,raw_distance," +
                    "pulse_valid,breath_valid,distance_valid" +
                    ") VALUES(?,?,?,?,?,?,?,?,?,?)";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    PreparedStatement ps = conn.prepareStatement(sql)
            ) {
                bindSingleRecord(ps, patientId, record);

                return ps.executeUpdate() > 0;

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        private static void bindSingleRecord(
                PreparedStatement ps,
                int patientId,
                RawRecord record
        ) throws SQLException {
            ps.setInt(1, patientId);
            ps.setLong(2, record.timestamp);
            ps.setDouble(3, record.rawPulse);
            ps.setDouble(4, record.filteredPulse);
            ps.setDouble(5, record.rawBreath);
            ps.setDouble(6, record.filteredBreath);
            ps.setDouble(7, record.rawDistance);
            ps.setInt(8, record.pulseValid ? 1 : 0);
            ps.setInt(9, record.breathValid ? 1 : 0);
            ps.setInt(10, record.distanceValid ? 1 : 0);
        }

        private static void bindRecord(
                PreparedStatement ps,
                int patientId,
                RawRecord record
        ) throws SQLException {
            bindSingleRecord(ps, patientId, record);
            ps.addBatch();
        }

        public List<ArchiveRecord> getAllMeasurements() {
            List<ArchiveRecord> list = new ArrayList<>();

            String sql =
                    "SELECT m.id, m.timestamp, " +
                    "m.filtered_pulse, m.filtered_breath, m.raw_distance, " +
                    "m.pulse_valid, m.breath_valid, m.distance_valid, " +
                    "COALESCE(p.full_name, 'Невідомий пацієнт') " +
                    "AS patient_name " +
                    "FROM measurements m " +
                    "LEFT JOIN patients p ON p.id = m.patient_id " +
                    "ORDER BY m.timestamp DESC, m.id DESC";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    Statement stmt = conn.createStatement();
                    ResultSet rs = stmt.executeQuery(sql)
            ) {
                while (rs.next()) {
                    list.add(readArchiveRecord(rs));
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            return list;
        }

        // Повертає унікальні дати обстежень пацієнта, новіші — першими
        public List<LocalDate> getMeasurementDates(int patientId) {
            Set<LocalDate> dates =
                    new TreeSet<>(Comparator.reverseOrder());

            String sql =
                    "SELECT timestamp FROM measurements " +
                    "WHERE patient_id = ? ORDER BY timestamp DESC";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    PreparedStatement ps = conn.prepareStatement(sql)
            ) {
                ps.setInt(1, patientId);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long timestamp = rs.getLong("timestamp");

                        LocalDate date =
                                Instant.ofEpochMilli(timestamp)
                                        .atZone(ZoneId.systemDefault())
                                        .toLocalDate();

                        dates.add(date);
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            return new ArrayList<>(dates);
        }

        // Повертає лише записи одного пацієнта за одну конкретну дату
        public List<ArchiveRecord> getMeasurementsForPatientAndDate(
                int patientId,
                LocalDate date
        ) {
            List<ArchiveRecord> records = new ArrayList<>();

            if (date == null) {
                return records;
            }

            ZoneId zone = ZoneId.systemDefault();

            long startTimestamp =
                    date.atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli();

            long endTimestamp =
                    date.plusDays(1)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli();

            String sql =
                    "SELECT m.id, m.timestamp, " +
                    "m.filtered_pulse, m.filtered_breath, m.raw_distance, " +
                    "m.pulse_valid, m.breath_valid, m.distance_valid, " +
                    "p.full_name AS patient_name " +
                    "FROM measurements m " +
                    "INNER JOIN patients p ON p.id = m.patient_id " +
                    "WHERE m.patient_id = ? " +
                    "AND m.timestamp >= ? AND m.timestamp < ? " +
                    "ORDER BY m.timestamp DESC, m.id DESC";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    PreparedStatement ps = conn.prepareStatement(sql)
            ) {
                ps.setInt(1, patientId);
                ps.setLong(2, startTimestamp);
                ps.setLong(3, endTimestamp);

                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        records.add(readArchiveRecord(rs));
                    }
                }
            } catch (SQLException e) {
                e.printStackTrace();
            }

            return records;
        }

        private static ArchiveRecord readArchiveRecord(
                ResultSet rs
        ) throws SQLException {
            return new ArchiveRecord(
                    rs.getInt("id"),
                    rs.getString("patient_name"),
                    rs.getLong("timestamp"),
                    rs.getDouble("filtered_pulse"),
                    rs.getDouble("filtered_breath"),
                    rs.getDouble("raw_distance"),
                    rs.getInt("pulse_valid") != 0,
                    rs.getInt("breath_valid") != 0,
                    rs.getInt("distance_valid") != 0
            );
        }

        public boolean deleteMeasurement(int id) {
            String sql =
                    "DELETE FROM measurements WHERE id = ?";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    PreparedStatement ps = conn.prepareStatement(sql)
            ) {
                ps.setInt(1, id);

                return ps.executeUpdate() > 0;

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        // Видаляє тільки вимірювання вибраного пацієнта за вибрану дату
        public boolean deleteMeasurementsForPatientAndDate(
                int patientId,
                LocalDate date
        ) {
            if (date == null) {
                return false;
            }

            ZoneId zone = ZoneId.systemDefault();

            long startTimestamp =
                    date.atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli();

            long endTimestamp =
                    date.plusDays(1)
                            .atStartOfDay(zone)
                            .toInstant()
                            .toEpochMilli();

            String sql =
                    "DELETE FROM measurements " +
                    "WHERE patient_id = ? " +
                    "AND timestamp >= ? AND timestamp < ?";

            try (
                    Connection conn = DriverManager.getConnection(URL);
                    PreparedStatement ps = conn.prepareStatement(sql)
            ) {
                ps.setInt(1, patientId);
                ps.setLong(2, startTimestamp);
                ps.setLong(3, endTimestamp);

                ps.executeUpdate();
                return true;

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }

        public boolean clearMeasurements() {
            try (
                    Connection conn = DriverManager.getConnection(URL);
                    Statement stmt = conn.createStatement()
            ) {
                stmt.executeUpdate("DELETE FROM measurements");
                return true;

            } catch (SQLException e) {
                e.printStackTrace();
                return false;
            }
        }
    }

    // MAIN
    public static void main(String[] args) {
        launch(args);
    }
}