package com.fclglucolink.app.calibration;

import android.database.Cursor;
import androidx.annotation.NonNull;
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
public final class CalibrationEntryDao_Impl implements CalibrationEntryDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<CalibrationEntryEntity> __insertionAdapterOfCalibrationEntryEntity;

  private final SharedSQLiteStatement __preparedStmtOfDelete;

  private final SharedSQLiteStatement __preparedStmtOfClearAll;

  private final SharedSQLiteStatement __preparedStmtOfClearAllForSensorType;

  private final SharedSQLiteStatement __preparedStmtOfSetIncludedForOrigin;

  private final SharedSQLiteStatement __preparedStmtOfSetIncludedForOther;

  public CalibrationEntryDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfCalibrationEntryEntity = new EntityInsertionAdapter<CalibrationEntryEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `calibration_entries` (`id`,`timestampMs`,`fingerstickMgdl`,`sensorMgdlAtPairing`,`sensorType`,`otherSensorType`,`otherSensorMgdlAtPairing`,`includedForOriginSensor`,`includedForOtherSensor`) VALUES (nullif(?, 0),?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final CalibrationEntryEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getTimestampMs());
        statement.bindDouble(3, entity.getFingerstickMgdl());
        statement.bindDouble(4, entity.getSensorMgdlAtPairing());
        if (entity.getSensorType() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getSensorType());
        }
        if (entity.getOtherSensorType() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getOtherSensorType());
        }
        if (entity.getOtherSensorMgdlAtPairing() == null) {
          statement.bindNull(7);
        } else {
          statement.bindDouble(7, entity.getOtherSensorMgdlAtPairing());
        }
        final int _tmp = entity.getIncludedForOriginSensor() ? 1 : 0;
        statement.bindLong(8, _tmp);
        final int _tmp_1 = entity.getIncludedForOtherSensor() ? 1 : 0;
        statement.bindLong(9, _tmp_1);
      }
    };
    this.__preparedStmtOfDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM calibration_entries WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfClearAll = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM calibration_entries";
        return _query;
      }
    };
    this.__preparedStmtOfClearAllForSensorType = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM calibration_entries WHERE sensorType = ?";
        return _query;
      }
    };
    this.__preparedStmtOfSetIncludedForOrigin = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE calibration_entries SET includedForOriginSensor = ? WHERE id = ?";
        return _query;
      }
    };
    this.__preparedStmtOfSetIncludedForOther = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE calibration_entries SET includedForOtherSensor = ? WHERE id = ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final CalibrationEntryEntity entry,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfCalibrationEntryEntity.insert(entry);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object delete(final long id, final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDelete.acquire();
        int _argIndex = 1;
        _stmt.bindLong(_argIndex, id);
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
          __preparedStmtOfDelete.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearAll(final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearAll.acquire();
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
          __preparedStmtOfClearAll.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object clearAllForSensorType(final String sensorType,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfClearAllForSensorType.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, sensorType);
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
          __preparedStmtOfClearAllForSensorType.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object setIncludedForOrigin(final long id, final boolean included,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetIncludedForOrigin.acquire();
        int _argIndex = 1;
        final int _tmp = included ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
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
          __preparedStmtOfSetIncludedForOrigin.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object setIncludedForOther(final long id, final boolean included,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfSetIncludedForOther.acquire();
        int _argIndex = 1;
        final int _tmp = included ? 1 : 0;
        _stmt.bindLong(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindLong(_argIndex, id);
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
          __preparedStmtOfSetIncludedForOther.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<CalibrationEntryEntity>> all() {
    final String _sql = "SELECT * FROM calibration_entries ORDER BY timestampMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"calibration_entries"}, new Callable<List<CalibrationEntryEntity>>() {
      @Override
      @NonNull
      public List<CalibrationEntryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfFingerstickMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerstickMgdl");
          final int _cursorIndexOfSensorMgdlAtPairing = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorMgdlAtPairing");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfOtherSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "otherSensorType");
          final int _cursorIndexOfOtherSensorMgdlAtPairing = CursorUtil.getColumnIndexOrThrow(_cursor, "otherSensorMgdlAtPairing");
          final int _cursorIndexOfIncludedForOriginSensor = CursorUtil.getColumnIndexOrThrow(_cursor, "includedForOriginSensor");
          final int _cursorIndexOfIncludedForOtherSensor = CursorUtil.getColumnIndexOrThrow(_cursor, "includedForOtherSensor");
          final List<CalibrationEntryEntity> _result = new ArrayList<CalibrationEntryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final CalibrationEntryEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final double _tmpFingerstickMgdl;
            _tmpFingerstickMgdl = _cursor.getDouble(_cursorIndexOfFingerstickMgdl);
            final double _tmpSensorMgdlAtPairing;
            _tmpSensorMgdlAtPairing = _cursor.getDouble(_cursorIndexOfSensorMgdlAtPairing);
            final String _tmpSensorType;
            if (_cursor.isNull(_cursorIndexOfSensorType)) {
              _tmpSensorType = null;
            } else {
              _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            }
            final String _tmpOtherSensorType;
            if (_cursor.isNull(_cursorIndexOfOtherSensorType)) {
              _tmpOtherSensorType = null;
            } else {
              _tmpOtherSensorType = _cursor.getString(_cursorIndexOfOtherSensorType);
            }
            final Double _tmpOtherSensorMgdlAtPairing;
            if (_cursor.isNull(_cursorIndexOfOtherSensorMgdlAtPairing)) {
              _tmpOtherSensorMgdlAtPairing = null;
            } else {
              _tmpOtherSensorMgdlAtPairing = _cursor.getDouble(_cursorIndexOfOtherSensorMgdlAtPairing);
            }
            final boolean _tmpIncludedForOriginSensor;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIncludedForOriginSensor);
            _tmpIncludedForOriginSensor = _tmp != 0;
            final boolean _tmpIncludedForOtherSensor;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIncludedForOtherSensor);
            _tmpIncludedForOtherSensor = _tmp_1 != 0;
            _item = new CalibrationEntryEntity(_tmpId,_tmpTimestampMs,_tmpFingerstickMgdl,_tmpSensorMgdlAtPairing,_tmpSensorType,_tmpOtherSensorType,_tmpOtherSensorMgdlAtPairing,_tmpIncludedForOriginSensor,_tmpIncludedForOtherSensor);
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
  public Flow<List<CalibrationEntryEntity>> allForSensorType(final String sensorType) {
    final String _sql = "SELECT * FROM calibration_entries WHERE sensorType = ? ORDER BY timestampMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, sensorType);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"calibration_entries"}, new Callable<List<CalibrationEntryEntity>>() {
      @Override
      @NonNull
      public List<CalibrationEntryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfFingerstickMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerstickMgdl");
          final int _cursorIndexOfSensorMgdlAtPairing = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorMgdlAtPairing");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfOtherSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "otherSensorType");
          final int _cursorIndexOfOtherSensorMgdlAtPairing = CursorUtil.getColumnIndexOrThrow(_cursor, "otherSensorMgdlAtPairing");
          final int _cursorIndexOfIncludedForOriginSensor = CursorUtil.getColumnIndexOrThrow(_cursor, "includedForOriginSensor");
          final int _cursorIndexOfIncludedForOtherSensor = CursorUtil.getColumnIndexOrThrow(_cursor, "includedForOtherSensor");
          final List<CalibrationEntryEntity> _result = new ArrayList<CalibrationEntryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final CalibrationEntryEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final double _tmpFingerstickMgdl;
            _tmpFingerstickMgdl = _cursor.getDouble(_cursorIndexOfFingerstickMgdl);
            final double _tmpSensorMgdlAtPairing;
            _tmpSensorMgdlAtPairing = _cursor.getDouble(_cursorIndexOfSensorMgdlAtPairing);
            final String _tmpSensorType;
            if (_cursor.isNull(_cursorIndexOfSensorType)) {
              _tmpSensorType = null;
            } else {
              _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            }
            final String _tmpOtherSensorType;
            if (_cursor.isNull(_cursorIndexOfOtherSensorType)) {
              _tmpOtherSensorType = null;
            } else {
              _tmpOtherSensorType = _cursor.getString(_cursorIndexOfOtherSensorType);
            }
            final Double _tmpOtherSensorMgdlAtPairing;
            if (_cursor.isNull(_cursorIndexOfOtherSensorMgdlAtPairing)) {
              _tmpOtherSensorMgdlAtPairing = null;
            } else {
              _tmpOtherSensorMgdlAtPairing = _cursor.getDouble(_cursorIndexOfOtherSensorMgdlAtPairing);
            }
            final boolean _tmpIncludedForOriginSensor;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIncludedForOriginSensor);
            _tmpIncludedForOriginSensor = _tmp != 0;
            final boolean _tmpIncludedForOtherSensor;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIncludedForOtherSensor);
            _tmpIncludedForOtherSensor = _tmp_1 != 0;
            _item = new CalibrationEntryEntity(_tmpId,_tmpTimestampMs,_tmpFingerstickMgdl,_tmpSensorMgdlAtPairing,_tmpSensorType,_tmpOtherSensorType,_tmpOtherSensorMgdlAtPairing,_tmpIncludedForOriginSensor,_tmpIncludedForOtherSensor);
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
  public Flow<List<CalibrationEntryEntity>> allRelevantForSensorType(final String sensorType) {
    final String _sql = "SELECT * FROM calibration_entries WHERE sensorType = ? OR otherSensorType = ? ORDER BY timestampMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindString(_argIndex, sensorType);
    _argIndex = 2;
    _statement.bindString(_argIndex, sensorType);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"calibration_entries"}, new Callable<List<CalibrationEntryEntity>>() {
      @Override
      @NonNull
      public List<CalibrationEntryEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfFingerstickMgdl = CursorUtil.getColumnIndexOrThrow(_cursor, "fingerstickMgdl");
          final int _cursorIndexOfSensorMgdlAtPairing = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorMgdlAtPairing");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final int _cursorIndexOfOtherSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "otherSensorType");
          final int _cursorIndexOfOtherSensorMgdlAtPairing = CursorUtil.getColumnIndexOrThrow(_cursor, "otherSensorMgdlAtPairing");
          final int _cursorIndexOfIncludedForOriginSensor = CursorUtil.getColumnIndexOrThrow(_cursor, "includedForOriginSensor");
          final int _cursorIndexOfIncludedForOtherSensor = CursorUtil.getColumnIndexOrThrow(_cursor, "includedForOtherSensor");
          final List<CalibrationEntryEntity> _result = new ArrayList<CalibrationEntryEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final CalibrationEntryEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final double _tmpFingerstickMgdl;
            _tmpFingerstickMgdl = _cursor.getDouble(_cursorIndexOfFingerstickMgdl);
            final double _tmpSensorMgdlAtPairing;
            _tmpSensorMgdlAtPairing = _cursor.getDouble(_cursorIndexOfSensorMgdlAtPairing);
            final String _tmpSensorType;
            if (_cursor.isNull(_cursorIndexOfSensorType)) {
              _tmpSensorType = null;
            } else {
              _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            }
            final String _tmpOtherSensorType;
            if (_cursor.isNull(_cursorIndexOfOtherSensorType)) {
              _tmpOtherSensorType = null;
            } else {
              _tmpOtherSensorType = _cursor.getString(_cursorIndexOfOtherSensorType);
            }
            final Double _tmpOtherSensorMgdlAtPairing;
            if (_cursor.isNull(_cursorIndexOfOtherSensorMgdlAtPairing)) {
              _tmpOtherSensorMgdlAtPairing = null;
            } else {
              _tmpOtherSensorMgdlAtPairing = _cursor.getDouble(_cursorIndexOfOtherSensorMgdlAtPairing);
            }
            final boolean _tmpIncludedForOriginSensor;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfIncludedForOriginSensor);
            _tmpIncludedForOriginSensor = _tmp != 0;
            final boolean _tmpIncludedForOtherSensor;
            final int _tmp_1;
            _tmp_1 = _cursor.getInt(_cursorIndexOfIncludedForOtherSensor);
            _tmpIncludedForOtherSensor = _tmp_1 != 0;
            _item = new CalibrationEntryEntity(_tmpId,_tmpTimestampMs,_tmpFingerstickMgdl,_tmpSensorMgdlAtPairing,_tmpSensorType,_tmpOtherSensorType,_tmpOtherSensorMgdlAtPairing,_tmpIncludedForOriginSensor,_tmpIncludedForOtherSensor);
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

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
