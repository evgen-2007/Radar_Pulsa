package com.pulsbreath.android;
import android.content.*;import android.database.Cursor;import android.database.sqlite.*;import com.pulsbreath.core.MathProcessor;import java.text.SimpleDateFormat;import java.util.*;
public final class AndroidDatabase extends SQLiteOpenHelper {
 public AndroidDatabase(Context c){super(c,"puls_breath.db",null,5);}
 @Override public void onCreate(SQLiteDatabase db){db.execSQL("CREATE TABLE IF NOT EXISTS measurements (id INTEGER PRIMARY KEY AUTOINCREMENT, measurement_date TEXT NOT NULL, measurement_time TEXT NOT NULL, pulse REAL, breath REAL, distance REAL, pathology_suspicion TEXT NOT NULL DEFAULT 'Недостатньо даних')");}
 @Override public void onUpgrade(SQLiteDatabase db,int oldVersion,int newVersion){
  // Add only absent columns; preserve all existing records and the legacy timestamp column if present.
  if(!hasColumn(db,"measurements","measurement_date"))db.execSQL("ALTER TABLE measurements ADD COLUMN measurement_date TEXT");
  if(!hasColumn(db,"measurements","measurement_time"))db.execSQL("ALTER TABLE measurements ADD COLUMN measurement_time TEXT");
  if(!hasColumn(db,"measurements","pathology_suspicion"))db.execSQL("ALTER TABLE measurements ADD COLUMN pathology_suspicion TEXT NOT NULL DEFAULT 'Недостатньо даних'");
  if(hasColumn(db,"measurements","timestamp"))db.execSQL("UPDATE measurements SET measurement_date=COALESCE(measurement_date,date(timestamp / 1000,'unixepoch')), measurement_time=COALESCE(measurement_time,time(timestamp / 1000,'unixepoch')) WHERE timestamp IS NOT NULL");
  db.execSQL("UPDATE measurements SET measurement_date=COALESCE(measurement_date,date('now','localtime')), measurement_time=COALESCE(measurement_time,time('now','localtime')) WHERE measurement_date IS NULL OR measurement_time IS NULL");
 }
 private boolean hasColumn(SQLiteDatabase db,String table,String column){try(Cursor c=db.rawQuery("PRAGMA table_info("+table+")",null)){while(c.moveToNext())if(column.equalsIgnoreCase(c.getString(c.getColumnIndexOrThrow("name"))))return true;}return false;}
 public synchronized void save(MathProcessor.MinuteSummary m){ContentValues v=new ContentValues();Date d=new Date(m.endTimestamp);v.put("measurement_date",new SimpleDateFormat("yyyy-MM-dd",Locale.getDefault()).format(d));v.put("measurement_time",new SimpleDateFormat("HH:mm:ss",Locale.getDefault()).format(d));if(m.pulseValid)v.put("pulse",m.pulse);else v.putNull("pulse");if(m.breathValid)v.put("breath",m.breath);else v.putNull("breath");if(m.distanceValid)v.put("distance",m.distanceCm);else v.putNull("distance");v.put("pathology_suspicion",m.pathologySuspicion);getWritableDatabase().insert("measurements",null,v);}
}
