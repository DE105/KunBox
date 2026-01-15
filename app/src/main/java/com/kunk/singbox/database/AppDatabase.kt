package com.kunk.singbox.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.kunk.singbox.database.dao.ActiveStateDao
import com.kunk.singbox.database.dao.NodeDao
import com.kunk.singbox.database.dao.NodeLatencyDao
import com.kunk.singbox.database.dao.ProfileDao
import com.kunk.singbox.database.entity.ActiveStateEntity
import com.kunk.singbox.database.entity.NodeEntity
import com.kunk.singbox.database.entity.NodeLatencyEntity
import com.kunk.singbox.database.entity.ProfileEntity

/**
 * 应用数据库
 *
 * 使用 Room 存储 Profile 和 Node 数据，替代 Kryo 文件存储
 *
 * 优势：
 * - 支持高效的查询和过滤
 * - 支持 Flow 实时观察数据变化
 * - 支持索引加速查询
 * - 内置事务支持
 */
@Database(
    entities = [
        ProfileEntity::class,
        NodeEntity::class,
        ActiveStateEntity::class,
        NodeLatencyEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun nodeDao(): NodeDao
    abstract fun activeStateDao(): ActiveStateDao
    abstract fun nodeLatencyDao(): NodeLatencyDao

    companion object {
        private const val DATABASE_NAME = "singbox.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                DATABASE_NAME
            )
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * 仅用于测试
         */
        fun getInMemoryDatabase(context: Context): AppDatabase {
            return Room.inMemoryDatabaseBuilder(
                context.applicationContext,
                AppDatabase::class.java
            ).build()
        }
    }
}
