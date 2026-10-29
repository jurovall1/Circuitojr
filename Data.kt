package com.rutas.circuito

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "stops")
data class Stop(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val position: Int = 0,
    val name: String = "",
    val address: String = "",
    val comuna: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val notes: String = "",
    val done: Boolean = false
)

@Dao
interface StopDao {
    @Query("SELECT * FROM stops ORDER BY position")
    fun observeAll(): Flow<List<Stop>>

    @Query("SELECT * FROM stops ORDER BY position")
    suspend fun getAll(): List<Stop>

    @Insert
    suspend fun insert(stop: Stop): Long

    @Insert
    suspend fun insertAll(stops: List<Stop>)

    @Update
    suspend fun update(stop: Stop)

    @Update
    suspend fun updateAll(stops: List<Stop>)

    @Delete
    suspend fun delete(stop: Stop)

    @Query("DELETE FROM stops")
    suspend fun clear()
}

@Database(entities = [Stop::class], version = 2, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): StopDao

    companion object {
        @Volatile private var instance: AppDb? = null
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE stops ADD COLUMN comuna TEXT NOT NULL DEFAULT ''")
            }
        }
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "rutas.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
