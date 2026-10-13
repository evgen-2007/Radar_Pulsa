package com.pulsbreath.desktop;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class PythonBleClient {

    public interface Listener {
        void onJson(String json);
        void onStatus(String text);
    }

    private final Listener listener;

    private Process process;
    private Thread reader;
    private Writer commandWriter;

    public PythonBleClient(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start() {
        stop();

        try {
            Path script = Path.of(
                    "desktop", "src", "main",
                    "resources", "ble_bridge.py"
            );

            if (!Files.exists(script)) {
                script = Path.of(
                        "src", "main", "resources",
                        "ble_bridge.py"
                );
            }

            if (!Files.exists(script)) {
                throw new FileNotFoundException(
                        "Не знайдено ble_bridge.py"
                );
            }

            ProcessBuilder builder = new ProcessBuilder(
                    "py",
                    "-3",
                    script.toAbsolutePath().toString()
            );

            builder.redirectErrorStream(true);

            Process startedProcess = builder.start();
            process = startedProcess;

            commandWriter = new OutputStreamWriter(
                    startedProcess.getOutputStream(),
                    StandardCharsets.UTF_8
            );

            reader = new Thread(() -> {
                try (BufferedReader input =
                             new BufferedReader(
                                     new InputStreamReader(
                                             startedProcess.getInputStream(),
                                             StandardCharsets.UTF_8
                                     )
                             )) {

                    String line;

                    while ((line = input.readLine()) != null) {
                        if (line.startsWith("JSON:")) {
                            listener.onJson(line.substring(5));
                        } else {
                            listener.onStatus(line);
                        }
                    }

                    if (!startedProcess.isAlive()) {
                        listener.onStatus(
                                "BLE: Python-міст завершив роботу."
                        );
                    }

                } catch (Exception e) {
                    listener.onStatus(
                            "BLE: помилка читання: " + e.getMessage()
                    );
                }
            }, "sensor-ble-reader");

            reader.setDaemon(true);
            reader.start();

            listener.onStatus("BLE: Python-міст запущено.");

        } catch (Exception e) {
            stop();

            listener.onStatus(
                    "BLE: не вдалося запустити Python-міст. "
                    + "Перевір Python 3 та bleak: "
                    + e.getMessage()
            );
        }
    }

    public synchronized boolean setRecording(boolean active) {
        if (process == null
                || !process.isAlive()
                || commandWriter == null) {

            listener.onStatus(
                    "BLE: міст не запущений; команду не передано."
            );
            return false;
        }

        try {
            commandWriter.write(active ? "START\n" : "STOP\n");
            commandWriter.flush();
            return true;

        } catch (Exception e) {
            listener.onStatus(
                    "BLE: помилка передавання команди: "
                    + e.getMessage()
            );
            return false;
        }
    }

    public synchronized void stop() {
        if (commandWriter != null) {
            try {
                commandWriter.close();
            } catch (Exception ignored) {
            }

            commandWriter = null;
        }

        if (process != null) {
            process.destroyForcibly();
            process = null;
        }
    }

    public synchronized boolean isRunning() {
        return process != null && process.isAlive();
    }
}