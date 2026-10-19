package com.feanor91.treadmill.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface TrainingDao {
    // Plans
    @Transaction @Query("SELECT * FROM plans ORDER BY name")
    fun observePlans(): Flow<List<PlanWithSteps>>

    @Transaction @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getPlan(id: Long): PlanWithSteps?

    @Query("SELECT COUNT(*) FROM plans WHERE name = :name") suspend fun planNameCount(name: String): Int
    @Insert suspend fun insertPlan(plan: PlanEntity): Long
    @Update suspend fun updatePlan(plan: PlanEntity)
    @Query("DELETE FROM plans WHERE id = :id") suspend fun deletePlan(id: Long)
    @Insert suspend fun insertSteps(steps: List<StepEntity>)
    @Query("DELETE FROM steps WHERE planId = :planId") suspend fun deleteSteps(planId: Long)

    // Programme
    @Transaction @Query("SELECT * FROM schedule WHERE epochDay >= :fromDay ORDER BY epochDay LIMIT :limit")
    fun observeUpcoming(fromDay: Long, limit: Int): Flow<List<ScheduledItem>>

    @Transaction @Query("SELECT * FROM schedule WHERE epochDay = :day ORDER BY id")
    fun observeDay(day: Long): Flow<List<ScheduledItem>>

    @Insert suspend fun insertSchedule(items: List<ScheduleEntity>)
    @Delete suspend fun deleteSchedule(item: ScheduleEntity)
    @Query("UPDATE schedule SET completed = 1 WHERE id = :id") suspend fun markDone(id: Long)

    // Historique
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("UPDATE sessions SET exported = 1 WHERE id = :id") suspend fun markExported(id: Long)
}

@Database(
    entities = [PlanEntity::class, StepEntity::class, ScheduleEntity::class, SessionEntity::class],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrainingDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN activeKcal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN exported INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
