package com.fclglucolink.app.data;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import com.fclglucolink.app.calibration.CalibrationEntryDao;
import com.fclglucolink.app.calibration.CalibrationEntryDao_Impl;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class FclGlucoLinkDatabase_Impl extends FclGlucoLinkDatabase {
  private volatile GlucoseReadingDao _glucoseReadingDao;

  private volatile CalibrationEntryDao _calibrationEntryDao;

  private volatile SensorSwitchEventDao _sensorSwitchEventDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(7) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `glucose_readings` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `glucoseMgdl` REAL NOT NULL, `trendMgdlPerMin` REAL NOT NULL, `timestampMs` INTEGER NOT NULL, `sensorStartedAtMs` INTEGER NOT NULL, `sensorType` TEXT NOT NULL, `rawSensorMgdl` REAL, `calibratedMgdl` REAL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `calibration_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestampMs` INTEGER NOT NULL, `fingerstickMgdl` REAL NOT NULL, `sensorMgdlAtPairing` REAL NOT NULL, `sensorType` TEXT, `otherSensorType` TEXT, `otherSensorMgdlAtPairing` REAL, `includedForOriginSensor` INTEGER NOT NULL, `includedForOtherSensor` INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE IF NOT EXISTS `sensor_switch_events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestampMs` INTEGER NOT NULL, `crossType` INTEGER NOT NULL, `sensorType` TEXT)");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '83ba757b6047f7797f61971f5d69dee7')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `glucose_readings`");
        db.execSQL("DROP TABLE IF EXISTS `calibration_entries`");
        db.execSQL("DROP TABLE IF EXISTS `sensor_switch_events`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsGlucoseReadings = new HashMap<String, TableInfo.Column>(8);
        _columnsGlucoseReadings.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("glucoseMgdl", new TableInfo.Column("glucoseMgdl", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("trendMgdlPerMin", new TableInfo.Column("trendMgdlPerMin", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("timestampMs", new TableInfo.Column("timestampMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("sensorStartedAtMs", new TableInfo.Column("sensorStartedAtMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("sensorType", new TableInfo.Column("sensorType", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("rawSensorMgdl", new TableInfo.Column("rawSensorMgdl", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsGlucoseReadings.put("calibratedMgdl", new TableInfo.Column("calibratedMgdl", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysGlucoseReadings = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesGlucoseReadings = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoGlucoseReadings = new TableInfo("glucose_readings", _columnsGlucoseReadings, _foreignKeysGlucoseReadings, _indicesGlucoseReadings);
        final TableInfo _existingGlucoseReadings = TableInfo.read(db, "glucose_readings");
        if (!_infoGlucoseReadings.equals(_existingGlucoseReadings)) {
          return new RoomOpenHelper.ValidationResult(false, "glucose_readings(com.fclglucolink.app.data.GlucoseReadingEntity).\n"
                  + " Expected:\n" + _infoGlucoseReadings + "\n"
                  + " Found:\n" + _existingGlucoseReadings);
        }
        final HashMap<String, TableInfo.Column> _columnsCalibrationEntries = new HashMap<String, TableInfo.Column>(9);
        _columnsCalibrationEntries.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("timestampMs", new TableInfo.Column("timestampMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("fingerstickMgdl", new TableInfo.Column("fingerstickMgdl", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("sensorMgdlAtPairing", new TableInfo.Column("sensorMgdlAtPairing", "REAL", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("sensorType", new TableInfo.Column("sensorType", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("otherSensorType", new TableInfo.Column("otherSensorType", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("otherSensorMgdlAtPairing", new TableInfo.Column("otherSensorMgdlAtPairing", "REAL", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("includedForOriginSensor", new TableInfo.Column("includedForOriginSensor", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsCalibrationEntries.put("includedForOtherSensor", new TableInfo.Column("includedForOtherSensor", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysCalibrationEntries = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesCalibrationEntries = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoCalibrationEntries = new TableInfo("calibration_entries", _columnsCalibrationEntries, _foreignKeysCalibrationEntries, _indicesCalibrationEntries);
        final TableInfo _existingCalibrationEntries = TableInfo.read(db, "calibration_entries");
        if (!_infoCalibrationEntries.equals(_existingCalibrationEntries)) {
          return new RoomOpenHelper.ValidationResult(false, "calibration_entries(com.fclglucolink.app.calibration.CalibrationEntryEntity).\n"
                  + " Expected:\n" + _infoCalibrationEntries + "\n"
                  + " Found:\n" + _existingCalibrationEntries);
        }
        final HashMap<String, TableInfo.Column> _columnsSensorSwitchEvents = new HashMap<String, TableInfo.Column>(4);
        _columnsSensorSwitchEvents.put("id", new TableInfo.Column("id", "INTEGER", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSensorSwitchEvents.put("timestampMs", new TableInfo.Column("timestampMs", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSensorSwitchEvents.put("crossType", new TableInfo.Column("crossType", "INTEGER", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSensorSwitchEvents.put("sensorType", new TableInfo.Column("sensorType", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysSensorSwitchEvents = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesSensorSwitchEvents = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoSensorSwitchEvents = new TableInfo("sensor_switch_events", _columnsSensorSwitchEvents, _foreignKeysSensorSwitchEvents, _indicesSensorSwitchEvents);
        final TableInfo _existingSensorSwitchEvents = TableInfo.read(db, "sensor_switch_events");
        if (!_infoSensorSwitchEvents.equals(_existingSensorSwitchEvents)) {
          return new RoomOpenHelper.ValidationResult(false, "sensor_switch_events(com.fclglucolink.app.data.SensorSwitchEventEntity).\n"
                  + " Expected:\n" + _infoSensorSwitchEvents + "\n"
                  + " Found:\n" + _existingSensorSwitchEvents);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "83ba757b6047f7797f61971f5d69dee7", "fc46a633fd8e7c8f844ae9b5aa88b1e7");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "glucose_readings","calibration_entries","sensor_switch_events");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `glucose_readings`");
      _db.execSQL("DELETE FROM `calibration_entries`");
      _db.execSQL("DELETE FROM `sensor_switch_events`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(GlucoseReadingDao.class, GlucoseReadingDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(CalibrationEntryDao.class, CalibrationEntryDao_Impl.getRequiredConverters());
    _typeConvertersMap.put(SensorSwitchEventDao.class, SensorSwitchEventDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public GlucoseReadingDao glucoseReadingDao() {
    if (_glucoseReadingDao != null) {
      return _glucoseReadingDao;
    } else {
      synchronized(this) {
        if(_glucoseReadingDao == null) {
          _glucoseReadingDao = new GlucoseReadingDao_Impl(this);
        }
        return _glucoseReadingDao;
      }
    }
  }

  @Override
  public CalibrationEntryDao calibrationEntryDao() {
    if (_calibrationEntryDao != null) {
      return _calibrationEntryDao;
    } else {
      synchronized(this) {
        if(_calibrationEntryDao == null) {
          _calibrationEntryDao = new CalibrationEntryDao_Impl(this);
        }
        return _calibrationEntryDao;
      }
    }
  }

  @Override
  public SensorSwitchEventDao sensorSwitchEventDao() {
    if (_sensorSwitchEventDao != null) {
      return _sensorSwitchEventDao;
    } else {
      synchronized(this) {
        if(_sensorSwitchEventDao == null) {
          _sensorSwitchEventDao = new SensorSwitchEventDao_Impl(this);
        }
        return _sensorSwitchEventDao;
      }
    }
  }
}
