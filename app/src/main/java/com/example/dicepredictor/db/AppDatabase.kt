package com.example.dicepredictor.db

import android.content.Context
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(
    tableName = "dice_results",
    indices = [Index(value = ["timestamp"])]
)
data class DiceResult(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val d1: Int,
    val d2: Int,
    val d3: Int,
    val sum: Int,
    val timestamp: Long = System.currentTimeMillis(),

    // Dự đoán đưa ra trước khi có kết quả
    val hasPred: Boolean = false,
    val predSum: Int = 0,
    val predWasT: Boolean = false,
    val predProbT: Double = 0.0,
    val sumCorrect: Boolean = false,
    val classCorrect: Boolean = false,

    // Đám đông
    val crowdChoice: Char = '?',   // 'T' / 'X' / '?' (không chắc)
    val crowdStrength: Int = 0     // giữ để tương thích schema, luôn = 0
)

@Database(
    entities = [DiceResult::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun diceDao(): DiceDao

    companion object {
        private const val DB_NAME = "dice.db"

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(ctx: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(ctx).also { INSTANCE = it }
            }

        private fun build(ctx: Context): AppDatabase =
            Room.databaseBuilder(ctx.applicationContext, AppDatabase::class.java, DB_NAME)
                .fallbackToDestructiveMigration()
                .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                .addCallback(object : Callback() {
                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        // Dùng query() cho PRAGMA vì có thể trả kết quả.
                        // execSQL() chỉ dùng cho câu lệnh KHÔNG trả kết quả.
                        runCatching {
                            db.query("PRAGMA wal_autocheckpoint=200").use { it.moveToFirst() }
                        }
                        runCatching {
                            db.query("PRAGMA synchronous=NORMAL").use { it.moveToFirst() }
                        }
                        runCatching {
                            db.query("PRAGMA temp_store=MEMORY").use { it.moveToFirst() }
                        }
                        runCatching {
                            db.query("PRAGMA cache_size=-2000").use { it.moveToFirst() }
                        }
                    }
                })
                .build()

        fun vacuum(db: AppDatabase) {
            runCatching { db.openHelper.writableDatabase.execSQL("VACUUM") }
        }

        fun checkpoint(db: AppDatabase) {
            runCatching {
                db.openHelper.writableDatabase
                    .query("PRAGMA wal_checkpoint(TRUNCATE)").close()
            }
        }
    }
}
