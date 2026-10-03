package ru.kubgau.attendance.terminal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Локальная база телефона преподавателя. Нужна, чтобы пара и касания
 * не терялись, пока ноутбук выключен или сеть пропала.
 */
@Database(entities = [SessionEntity::class, TapEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun tapDao(): TapDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        /** v1 → v2: у касаний появились phone_id и уровень сигнала (BLE-режим). */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE taps ADD COLUMN deviceId TEXT")
                database.execSQL("ALTER TABLE taps ADD COLUMN rssi INTEGER")
            }
        }

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kubgau_terminal.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
