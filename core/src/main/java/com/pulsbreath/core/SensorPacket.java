package com.pulsbreath.core;

/** Platform-neutral representation of the JSON packet sent by XIAO ESP32-C6. */
public final class SensorPacket {
    public final boolean target, heartValid, breathValid, distanceValid, recording, wifiConnected, bleConnected;
    public final double heart, breath, distanceCm;
    private SensorPacket(boolean target, double heart, boolean heartValid, double breath, boolean breathValid,
                         double distanceCm, boolean distanceValid, boolean recording, boolean wifiConnected, boolean bleConnected) {
        this.target=target; this.heart=heart; this.heartValid=heartValid; this.breath=breath; this.breathValid=breathValid;
        this.distanceCm=distanceCm; this.distanceValid=distanceValid; this.recording=recording; this.wifiConnected=wifiConnected; this.bleConnected=bleConnected;
    }
    public static SensorPacket parse(String json) {
        if (json == null) throw new IllegalArgumentException("Порожня відповідь сенсора");
        return new SensorPacket(bool(json,"target"), num(json,"heart"), bool(json,"heart_valid"), num(json,"breath"),
                bool(json,"breath_valid"), num(json,"distance"), bool(json,"distance_valid"), bool(json,"recording"),
                bool(json,"wifi_connected"), bool(json,"ble_connected"));
    }
    private static String value(String j, String key) {
        java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\\""+java.util.regex.Pattern.quote(key)+"\\\"\\s*:\\s*(\\\"[^\\\"]*\\\"|true|false|null|-?\\d+(?:\\.\\d+)?)").matcher(j);
        return m.find()?m.group(1):null;
    }
    private static boolean bool(String j,String k){ String v=value(j,k); return "true".equalsIgnoreCase(v); }
    private static double num(String j,String k){ try { String v=value(j,k); return v==null?0:Double.parseDouble(v); } catch(Exception e){ return 0; } }
}
