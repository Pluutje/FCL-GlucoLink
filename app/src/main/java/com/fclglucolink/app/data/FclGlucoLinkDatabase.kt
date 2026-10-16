package com.fclglucolink.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.fclglucolink.app.calibration.CalibrationEntryDao
import com.fclglucolink.app.calibration.CalibrationEntryEntity

/**
 * 05/08/2026 (editor, RONDE 43) — versie 1 -> 2: nieuwe `calibration_entries`-
 * tabel voor de kalibratiefunctie (zie CalibrationEntryEntity's kdoc). Een
 * losse `Migration` i.p.v. `fallbackToDestructiveMigration()` — de bestaande
 * `glucose_readings`-tabel is weliswaar zelf "maar" een grafiek-cache (zie
 * GlucoseReadingEntity's kdoc), maar een gewone CREATE TABLE hier kost niets
 * extra en voorkomt zelfs die kleine, onnodige data-loss-verrassing bij een
 * update.
 */
private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `calibration_entries` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`fingerstickMgdl` REAL NOT NULL, " +
                "`sensorMgdlAtPairing` REAL NOT NULL)"
        )
        // Nullable kolom, geen DEFAULT nodig — zie GlucoseReadingEntity.
        // rawSensorMgdl's kdoc voor waarom bestaande rijen bewust null
        // blijven i.p.v. een 0.0-default.
        db.execSQL("ALTER TABLE `glucose_readings` ADD COLUMN `rawSensorMgdl` REAL")
    }
}

/** 09/08/2026 (editor, RONDE 64) — versie 2 -> 3: nieuwe `sensor_switch_
 *  events`-tabel voor de sensor-wisselmarkers op de BG-grafiek, zie
 *  SensorSwitchEventEntity.kt's kdoc. Zelfde "gewoon een CREATE TABLE
 *  i.p.v. destructive migration"-redenering als MIGRATION_1_2 hierboven. */
private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sensor_switch_events` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`timestampMs` INTEGER NOT NULL, " +
                "`crossType` INTEGER NOT NULL)"
        )
    }
}

/** 10/08/2026 (editor, RONDE 79 — 2-sensoren-architectuur) — versie 3 -> 4:
 *  nieuwe nullable `sensorType`-kolom op `calibration_entries`, zie
 *  CalibrationEntryEntity's kdoc bij dat veld voor de cross-slot-vervuilings-
 *  /wegveeg-bug die dit voorkomt. Zelfde "gewoon ALTER TABLE i.p.v.
 *  destructive migration"-redenering als de eerdere migraties hierboven. */
private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calibration_entries` ADD COLUMN `sensorType` TEXT")
    }
}

/** 10/08/2026 (editor, RONDE 84 — BUGFIX na live-melding met screenshots)
 *  — versie 4 -> 5: nieuwe nullable `sensorType`-kolom op
 *  `sensor_switch_events`, zie SensorSwitchEventEntity.kt's kdoc bij dat
 *  veld voor de cross-slot-lek (een wisselmarker verscheen op ELK
 *  tabblad's grafiek) die dit oploste. Zelfde "ALTER TABLE i.p.v.
 *  destructive migration"-redenering als MIGRATION_3_4 hierboven. */
private val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `sensor_switch_events` ADD COLUMN `sensorType` TEXT")
    }
}

/** 11/08/2026 (editor, RONDE 90 — op verzoek: gedeelde vingerprik-database
 *  tussen beide slots) — versie 5 -> 6: vier nieuwe kolommen op
 *  `calibration_entries`, zie CalibrationEntryEntity.kt's kdoc bij die
 *  velden. `includedForOriginSensor`/`includedForOtherSensor` krijgen een
 *  expliciete SQL-DEFAULT (1/0) zodat bestaande rijen zich na de migratie
 *  precies gedragen als vóór deze ronde (aangevinkt voor hun herkomst-
 *  sensor, niet voor een ander sensortype — er was toen toch nog geen
 *  ander sensortype om aan te koppelen). Zelfde "ALTER TABLE i.p.v.
 *  destructive migration"-redenering als de eerdere migraties hierboven. */
private val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `calibration_entries` ADD COLUMN `otherSensorType` TEXT")
        db.execSQL("ALTER TABLE `calibration_entries` ADD COLUMN `otherSensorMgdlAtPairing` REAL")
        db.execSQL("ALTER TABLE `calibration_entries` ADD COLUMN `includedForOriginSensor` INTEGER NOT NULL DEFAULT 1")
        db.execSQL("ALTER TABLE `calibration_entries` ADD COLUMN `includedForOtherSensor` INTEGER NOT NULL DEFAULT 0")
    }
}

/** 21/08/2026 (editor, RONDE 119 — BUGFIX) — versie 6 -> 7: nieuwe nullable
 *  `calibratedMgdl`-kolom op `glucose_readings`, zie GlucoseReadingEntity.kt's
 *  kdoc bij dat veld voor de volledige aanleiding (het veld ontbrak
 *  volledig, waardoor StatusScreen.kt's pijplijn-rij "Calibrated" in
 *  werkelijkheid altijd gewoon Filtered nogmaals liet zien). Zelfde
 *  "ALTER TABLE i.p.v. destructive migration"-redenering als de eerdere
 *  migraties hierboven. */
private val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `glucose_readings` ADD COLUMN `calibratedMgdl` REAL")
    }
}

/** 28/08/2026 (editor, RONDE 153, CRITIEKE FIX) — versie 7 -> 8: nieuwe
 *  nullable `slot`-kolom op `glucose_readings`, zie GlucoseReadingEntity.kt's
 *  kdoc bij dat veld voor de volledige aanleiding (twee gelijktijdig
 *  gekoppelde sensoren van HETZELFDE type konden hun metingen niet meer uit
 *  elkaar houden, omdat `sensorType` de enige per-slot-filtersleutel was).
 *  Zelfde "ALTER TABLE i.p.v. destructive migration"-redenering als de
 *  eerdere migraties hierboven — bestaande rijen krijgen `NULL` (geen bekende
 *  slot) i.p.v. te worden gewist; ze vallen simpelweg buiten de nieuwe
 *  slot-gefilterde per-tab-queries totdat ze het 48u-venster uitgroeien. */
private val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `glucose_readings` ADD COLUMN `slot` TEXT")
    }
}

@Database(
    entities = [GlucoseReadingEntity::class, CalibrationEntryEntity::class, SensorSwitchEventEntity::class],
    version = 8,
    exportSchema = false
)
abstract class FclGlucoLinkDatabase : RoomDatabase() {

    abstract fun glucoseReadingDao(): GlucoseReadingDao
    abstract fun calibrationEntryDao(): CalibrationEntryDao
    abstract fun sensorSwitchEventDao(): SensorSwitchEventDao

    companion object {
        @Volatile private var instance: FclGlucoLinkDatabase? = null

        fun getInstance(context: Context): FclGlucoLinkDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FclGlucoLinkDatabase::class.java,
                    "fclglucolink.db"
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8).build().also { instance = it }
            }
    }
}
