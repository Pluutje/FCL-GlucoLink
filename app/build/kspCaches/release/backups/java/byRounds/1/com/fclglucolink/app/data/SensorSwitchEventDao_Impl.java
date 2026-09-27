package com.fclglucolink.app.data;

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
public final class SensorSwitchEventDao_Impl implements SensorSwitchEventDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<SensorSwitchEventEntity> __insertionAdapterOfSensorSwitchEventEntity;

  private final SharedSQLiteStatement __preparedStmtOfDeleteOlderThan;

  public SensorSwitchEventDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfSensorSwitchEventEntity = new EntityInsertionAdapter<SensorSwitchEventEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR ABORT INTO `sensor_switch_events` (`id`,`timestampMs`,`crossType`,`sensorType`) VALUES (nullif(?, 0),?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final SensorSwitchEventEntity entity) {
        statement.bindLong(1, entity.getId());
        statement.bindLong(2, entity.getTimestampMs());
        final int _tmp = entity.getCrossType() ? 1 : 0;
        statement.bindLong(3, _tmp);
        if (entity.getSensorType() == null) {
          statement.bindNull(4);
        } else {
          statement.bindString(4, entity.getSensorType());
        }
      }
    };
    this.__preparedStmtOfDeleteOlderThan = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM sensor_switch_events WHERE timestampMs < ?";
        return _query;
      }
    };
  }

  @Override
  public Object insert(final SensorSwitchEventEntity event,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfSensorSwitchEventEntity.insert(event);
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
  public Flow<List<SensorSwitchEventEntity>> recentEventsForSensorType(final long sinceMs,
      final String sensorType) {
    final String _sql = "SELECT * FROM sensor_switch_events WHERE timestampMs >= ? AND sensorType = ? ORDER BY timestampMs ASC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 2);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, sinceMs);
    _argIndex = 2;
    _statement.bindString(_argIndex, sensorType);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"sensor_switch_events"}, new Callable<List<SensorSwitchEventEntity>>() {
      @Override
      @NonNull
      public List<SensorSwitchEventEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfId = CursorUtil.getColumnIndexOrThrow(_cursor, "id");
          final int _cursorIndexOfTimestampMs = CursorUtil.getColumnIndexOrThrow(_cursor, "timestampMs");
          final int _cursorIndexOfCrossType = CursorUtil.getColumnIndexOrThrow(_cursor, "crossType");
          final int _cursorIndexOfSensorType = CursorUtil.getColumnIndexOrThrow(_cursor, "sensorType");
          final List<SensorSwitchEventEntity> _result = new ArrayList<SensorSwitchEventEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final SensorSwitchEventEntity _item;
            final long _tmpId;
            _tmpId = _cursor.getLong(_cursorIndexOfId);
            final long _tmpTimestampMs;
            _tmpTimestampMs = _cursor.getLong(_cursorIndexOfTimestampMs);
            final boolean _tmpCrossType;
            final int _tmp;
            _tmp = _cursor.getInt(_cursorIndexOfCrossType);
            _tmpCrossType = _tmp != 0;
            final String _tmpSensorType;
            if (_cursor.isNull(_cursorIndexOfSensorType)) {
              _tmpSensorType = null;
            } else {
              _tmpSensorType = _cursor.getString(_cursorIndexOfSensorType);
            }
            _item = new SensorSwitchEventEntity(_tmpId,_tmpTimestampMs,_tmpCrossType,_tmpSensorType);
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
