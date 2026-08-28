package com.fclglucolink.app.data;

import android.database.Cursor;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import java.lang.Class;
import java.lang.Double;
import java.lang.Exception;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class GlucoseReadingDao_Impl implements GlucoseReadingDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<GlucoseReadingEntity> __insertionAdapterOfGlucoseReadingEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteOlderThan;

  private final SharedSQLiteStatement __preparedStmtOfDeleteFrom;

  private final SharedSQLiteStatement __preparedStmtOfDeleteFromForSlot;

  public GlucoseReadingDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfGlucoseReadingEntity = new EntityInsertionAdapter<GlucoseReadingEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `glucose_readings` (`id`,`glucoseMgdl`,`trendMgdlPerMin`,`timestampMs`,`sensorStartedAtMs`,`sensorType`,`rawSensorMgdl`,`calibratedMgdl`,`slot`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final GlucoseReadingEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindDouble(2, entity.getGlucoseMgdl());
        statement.bindDouble(3, entity.getTrendMgdlPerMin());
        statement.bindLong(4, entity.getTimestampMs());
        statement.bindLong(5, entity.getSensorStartedAtMs());
        statement.bindString(6, entity.getSensorType());
        if (entity.getRawSensorMgdl() == null) {
          statement.bindNull(7);
        } else {
          statement.bindDouble(7, entity.getRawSensorMgdl());
        }
        if (entity.getCalibratedMgdl() == null) {
          statement.bindNull(8);
        } else {
          statement.bindDouble(8, entity.getCalibratedMgdl());
        }
        if (entity.getSlot() == null) {
          statement.bindNull(9);
        } else {
          statement.bindString(9, entity.getSlot());
        }
      }
    };
    this.__preparedStmtOfDeleteOlderThan = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM glucose_readings WHERE timestampMs < ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteFrom = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM glucose_readings WHERE timestampMs >= ?";
        return _query;
      }
    };
    this.__preparedStmtOfDeleteFromForSlot = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM glucose_readings WHERE timestampMs >= ? AND slot = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final GlucoseReadingEntity reading,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfGlucoseReadingEntity.insert(reading);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteOlderThan(final long beforeMs, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteOlderThan.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, beforeMs);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteOlderThan.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteFrom(final long fromMs, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteFrom.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, fromMs);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteFrom.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteFromForSlot(final long fromMs, final String slot,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteFromForSlot.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, fromMs);
        _argIndex = 2;
        _stmt.bindString(_argIndex, slot);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteFromForSlot.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<GlucoseReadingEntity>> recentReadings(final long sinceMs) {
    final String _sql = "SELECT * FROM glucose_readings WHERE timestampMs >= ? ORDER BY timestampMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sinceMs);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"glucose_readings"}, new Callable<List<GlucoseReadingEntity>>() {
      @Override
      @NonNull
      public List<GlucoseReadingEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfGlucoseMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "glucoseMgdl");
          final int _cursorIndexOfTrendMgdlPerMin = CursorUtil.getColumnIndexOrThrow(_cursor, "trendMgdlPerMin");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfSensorStartedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorStartedAtMs");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfRawSensorMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "rawSensorMgdl");
          final int _cursorIndexOfCalibratedMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "calibratedMgdl");
          final int _cursorIndexOfSlot = CursorUtil.getColumnIndexOrThrow(_cursor, "slot");
          final List<GlucoseReadingEntity> _result = new ArrayList<GlucoseReadingEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final GlucoseReadingEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final double _tmpGlucoseMgdl;
            _tmpGlucoseMgdl = _cursor.getDouble(_cursorIndexOfGlucoseMgdl);
            final float _tmpTrendMgdlPerMin;
            _tmpTrendMgdlPerMin = _cursor.getFloat(_cursorIndexOfTrendMgdlPerMin);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final long _tmpSensorStartedAtMs;
            _tmpSensorStartedAtMs = _cursor.getLong(_cursorIndexOfSensorStartedAtMs);
            final String _tmpSensorType;
            _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            final Double _tmpRawSensorMgdl;
            if (_cursor.isNull(_cursorIndexOfRawSensorMgdl)) {
              _tmpRawSensorMgdl = null;
            } else {
              _tmpRawSensorMgdl = _cursor.getDouble(_cursorIndexOfRawSensorMgdl);
            }
            final Double _tmpCalibratedMgdl;
            if (_cursor.isNull(_cursorIndexOfCalibratedMgdl)) {
              _tmpCalibratedMgdl = null;
            } else {
              _tmpCalibratedMgdl = _cursor.getDouble(_cursorIndexOfCalibratedMgdl);
            }
            final String _tmpSlot;
            if (_cursor.isNull(_cursorIndexOfSlot)) {
              _tmpSlot = null;
            } else {
              _tmpSlot = _cursor.getString(_cursorIndexOfSlot);
            }
            _item = new GlucoseReadingEntity(_tmpId,_tmpGlucoseMgdl,_tmpTrendMgdlPerMin,_tmpTimestampMs,_tmpSensorStartedAtMs,_tmpSensorType,_tmpRawSensorMgdl,_tmpCalibratedMgdl,_tmpSlot);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<GlucoseReadingEntity> latestReading() {
    final String _sql = "SELECT * FROM glucose_readings ORDER BY timestampMs DESC LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"glucose_readings"}, new Callable<GlucoseReadingEntity>() {
      @Override
      @Nullable
      public GlucoseReadingEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfGlucoseMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "glucoseMgdl");
          final int _cursorIndexOfTrendMgdlPerMin = CursorUtil.getColumnIndexOrThrow(_cursor, "trendMgdlPerMin");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfSensorStartedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorStartedAtMs");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfRawSensorMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "rawSensorMgdl");
          final int _cursorIndexOfCalibratedMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "calibratedMgdl");
          final int _cursorIndexOfSlot = CursorUtil.getColumnIndexOrThrow(_cursor, "slot");
          final GlucoseReadingEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final double _tmpGlucoseMgdl;
            _tmpGlucoseMgdl = _cursor.getDouble(_cursorIndexOfGlucoseMgdl);
            final float _tmpTrendMgdlPerMin;
            _tmpTrendMgdlPerMin = _cursor.getFloat(_cursorIndexOfTrendMgdlPerMin);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final long _tmpSensorStartedAtMs;
            _tmpSensorStartedAtMs = _cursor.getLong(_cursorIndexOfSensorStartedAtMs);
            final String _tmpSensorType;
            _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            final Double _tmpRawSensorMgdl;
            if (_cursor.isNull(_cursorIndexOfRawSensorMgdl)) {
              _tmpRawSensorMgdl = null;
            } else {
              _tmpRawSensorMgdl = _cursor.getDouble(_cursorIndexOfRawSensorMgdl);
            }
            final Double _tmpCalibratedMgdl;
            if (_cursor.isNull(_cursorIndexOfCalibratedMgdl)) {
              _tmpCalibratedMgdl = null;
            } else {
              _tmpCalibratedMgdl = _cursor.getDouble(_cursorIndexOfCalibratedMgdl);
            }
            final String _tmpSlot;
            if (_cursor.isNull(_cursorIndexOfSlot)) {
              _tmpSlot = null;
            } else {
              _tmpSlot = _cursor.getString(_cursorIndexOfSlot);
            }
            _result = new GlucoseReadingEntity(_tmpId,_tmpGlucoseMgdl,_tmpTrendMgdlPerMin,_tmpTimestampMs,_tmpSensorStartedAtMs,_tmpSensorType,_tmpRawSensorMgdl,_tmpCalibratedMgdl,_tmpSlot);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<GlucoseReadingEntity>> recentReadingsForSlot(final long sinceMs,
      final String slot) {
    final String _sql = "SELECT * FROM glucose_readings WHERE timestampMs >= ? AND slot = ? ORDER BY timestampMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sinceMs);
    _argIndex = 2;
    _statement.bindString(_argIndex, slot);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"glucose_readings"}, new Callable<List<GlucoseReadingEntity>>() {
      @Override
      @NonNull
      public List<GlucoseReadingEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfGlucoseMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "glucoseMgdl");
          final int _cursorIndexOfTrendMgdlPerMin = CursorUtil.getColumnIndexOrThrow(_cursor, "trendMgdlPerMin");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfSensorStartedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorStartedAtMs");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfRawSensorMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "rawSensorMgdl");
          final int _cursorIndexOfCalibratedMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "calibratedMgdl");
          final int _cursorIndexOfSlot = CursorUtil.getColumnIndexOrThrow(_cursor, "slot");
          final List<GlucoseReadingEntity> _result = new ArrayList<GlucoseReadingEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final GlucoseReadingEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final double _tmpGlucoseMgdl;
            _tmpGlucoseMgdl = _cursor.getDouble(_cursorIndexOfGlucoseMgdl);
            final float _tmpTrendMgdlPerMin;
            _tmpTrendMgdlPerMin = _cursor.getFloat(_cursorIndexOfTrendMgdlPerMin);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final long _tmpSensorStartedAtMs;
            _tmpSensorStartedAtMs = _cursor.getLong(_cursorIndexOfSensorStartedAtMs);
            final String _tmpSensorType;
            _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            final Double _tmpRawSensorMgdl;
            if (_cursor.isNull(_cursorIndexOfRawSensorMgdl)) {
              _tmpRawSensorMgdl = null;
            } else {
              _tmpRawSensorMgdl = _cursor.getDouble(_cursorIndexOfRawSensorMgdl);
            }
            final Double _tmpCalibratedMgdl;
            if (_cursor.isNull(_cursorIndexOfCalibratedMgdl)) {
              _tmpCalibratedMgdl = null;
            } else {
              _tmpCalibratedMgdl = _cursor.getDouble(_cursorIndexOfCalibratedMgdl);
            }
            final String _tmpSlot;
            if (_cursor.isNull(_cursorIndexOfSlot)) {
              _tmpSlot = null;
            } else {
              _tmpSlot = _cursor.getString(_cursorIndexOfSlot);
            }
            _item = new GlucoseReadingEntity(_tmpId,_tmpGlucoseMgdl,_tmpTrendMgdlPerMin,_tmpTimestampMs,_tmpSensorStartedAtMs,_tmpSensorType,_tmpRawSensorMgdl,_tmpCalibratedMgdl,_tmpSlot);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<GlucoseReadingEntity> latestReadingForSlot(final String slot) {
    final String _sql = "SELECT * FROM glucose_readings WHERE slot = ? ORDER BY timestampMs DESC LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, slot);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"glucose_readings"}, new Callable<GlucoseReadingEntity>() {
      @Override
      @Nullable
      public GlucoseReadingEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfGlucoseMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "glucoseMgdl");
          final int _cursorIndexOfTrendMgdlPerMin = CursorUtil.getColumnIndexOrThrow(_cursor, "trendMgdlPerMin");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfSensorStartedAtMs = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorStartedAtMs");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfRawSensorMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "rawSensorMgdl");
          final int _cursorIndexOfCalibratedMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "calibratedMgdl");
          final int _cursorIndexOfSlot = CursorUtil.getColumnIndexOrThrow(_cursor, "slot");
          final GlucoseReadingEntity _result;
          if (_cursor.moveToFirst()) {
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final double _tmpGlucoseMgdl;
            _tmpGlucoseMgdl = _cursor.getDouble(_cursorIndexOfGlucoseMgdl);
            final float _tmpTrendMgdlPerMin;
            _tmpTrendMgdlPerMin = _cursor.getFloat(_cursorIndexOfTrendMgdlPerMin);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final long _tmpSensorStartedAtMs;
            _tmpSensorStartedAtMs = _cursor.getLong(_cursorIndexOfSensorStartedAtMs);
            final String _tmpSensorType;
            _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            final Double _tmpRawSensorMgdl;
            if (_cursor.isNull(_cursorIndexOfRawSensorMgdl)) {
              _tmpRawSensorMgdl = null;
            } else {
              _tmpRawSensorMgdl = _cursor.getDouble(_cursorIndexOfRawSensorMgdl);
            }
            final Double _tmpCalibratedMgdl;
            if (_cursor.isNull(_cursorIndexOfCalibratedMgdl)) {
              _tmpCalibratedMgdl = null;
            } else {
              _tmpCalibratedMgdl = _cursor.getDouble(_cursorIndexOfCalibratedMgdl);
            }
            final String _tmpSlot;
            if (_cursor.isNull(_cursorIndexOfSlot)) {
              _tmpSlot = null;
            } else {
              _tmpSlot = _cursor.getString(_cursorIndexOfSlot);
            }
            _result = new GlucoseReadingEntity(_tmpId,_tmpGlucoseMgdl,_tmpTrendMgdlPerMin,_tmpTimestampMs,_tmpSensorStartedAtMs,_tmpSensorType,_tmpRawSensorMgdl,_tmpCalibratedMgdl,_tmpSlot);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
