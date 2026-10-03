package ru.kubgau.attendance.terminal.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Локальная база телефона преподавателя. Нужна, чтобы пара и касания
 * не терялись, пока ноутбук выключен или сеть пропала.
 */
@Database(entities = [SessionEntity::class, TapEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {

    abstract fun sessionDao(): SessionDao
    abstract fun tapDao(): TapDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "kubgau_terminal.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
