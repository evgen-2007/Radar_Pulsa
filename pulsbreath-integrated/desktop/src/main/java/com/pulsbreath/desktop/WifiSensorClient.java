package com.pulsbreath.desktop;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Receives sensor data over HTTP and sends explicit START/STOP commands.
 */
public final class WifiSensorClient {

    public interface Listener {
        void onJson(String json);
        void onError(String error);
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .build();

    private final Listener listener;

    private ScheduledExecutorService pollExecutor;
    private ScheduledExecutorService commandExecutor;

    private volatile boolean running;
    private volatile String baseUrl;

    public WifiSensorClient(Listener listener) {
        this.listener = listener;
    }

    public synchronized void start(
            String baseUrl,
            boolean isRecording
    ) {
        stop();

        this.baseUrl = normalizeBaseUrl(baseUrl);
        running = true;

        pollExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sensor-wifi-poll");
            t.setDaemon(true);
            return t;
        });

        commandExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sensor-wifi-command");
            t.setDaemon(true);
            return t;
        });

        // Regularly poll the sensor data.
        pollExecutor.scheduleWithFixedDelay(() -> {
            if (!running) {
                return;
            }

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(this.baseUrl + "/api/sensor"))
                        .timeout(Duration.ofSeconds(6))
                        .GET()
                        .build();

                HttpResponse<String> response = HTTP.send(
                        request,
                        HttpResponse.BodyHandlers.ofString()
                );

                if (response.statusCode() != 200) {
                    throw new IllegalStateException(
                            "HTTP " + response.statusCode()
                    );
                }

                listener.onJson(response.body());

            } catch (Exception e) {
                if (running) {
                    listener.onError("Wi-Fi: " + e.getMessage());
                }
            }
        }, 0, 1500, TimeUnit.MILLISECONDS);

        // Synchronize the device to the UI's current recording state.
        setRecording(isRecording);
    }

    //Sends an explicit START or STOP command to the ESP32.
    
    public void setRecording(boolean active) {
    ScheduledExecutorService executor = commandExecutor;
    String urlBase = baseUrl;

    if (!running || executor == null || urlBase == null) {
        return;
    }

    executor.execute(() -> {
        if (!running) {
            return;
        }

        try {
            String endpoint = "/api/recording/toggle";
            System.out.println(
                "[WIFI RECORDING] POST " + urlBase + endpoint
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(urlBase + endpoint))
                    .timeout(Duration.ofSeconds(6))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<String> response = HTTP.send(
                    request,
                    HttpResponse.BodyHandlers.ofString()
            );

            if (response.statusCode() != 200) {
                if (response.statusCode() != 200) {
                    System.out.println(
                        "[WIFI RECORDING] HTTP " + response.statusCode()
                    );

                    System.out.println(
                        "[WIFI RECORDING] Response: " + response.body()
                    );

                    throw new IllegalStateException(
                        "URL: " + urlBase + endpoint
                        + "; HTTP: " + response.statusCode()
                            + "; Response: " + response.body()
                    );
                }

                System.out.println(
                        "[WIFI RECORDING] Success: " + response.body()
                );
            }

        } catch (Exception e) {
            if (running) {
                listener.onError(
                        "Wi-Fi recording command: " + e.getMessage()
                );
            }
        }
    });
}

    private static String normalizeBaseUrl(String value) {
        String result = value == null ? "" : value.trim();

        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }

        return result;
    }

    public synchronized void stop() {
        running = false;

        if (pollExecutor != null) {
            pollExecutor.shutdownNow();
            pollExecutor = null;
        }

        if (commandExecutor != null) {
            commandExecutor.shutdownNow();
            commandExecutor = null;
        }
    }
}