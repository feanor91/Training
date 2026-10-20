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
    @Transaction @Query("SELECT * FROM plans WHERE programId IS NULL ORDER BY favorite DESC, name")
    fun observePlans(): Flow<List<PlanWithSteps>>

    @Transaction @Query("SELECT * FROM plans WHERE programId IS NULL ORDER BY favorite DESC, name")
    suspend fun plansOnce(): List<PlanWithSteps>

    @Transaction @Query("SELECT * FROM plans WHERE id = :id")
    suspend fun getPlan(id: Long): PlanWithSteps?

    @Query("SELECT COUNT(*) FROM plans WHERE name = :name AND programId IS NULL") suspend fun planNameCount(name: String): Int
    @Insert suspend fun insertPlan(plan: PlanEntity): Long
    @Update suspend fun updatePlan(plan: PlanEntity)
    @Query("DELETE FROM plans WHERE id = :id") suspend fun deletePlan(id: Long)
    @Insert suspend fun insertSteps(steps: List<StepEntity>)
    @Query("DELETE FROM steps WHERE planId = :planId") suspend fun deleteSteps(planId: Long)

    @Query("UPDATE plans SET favorite = :fav WHERE id = :id") suspend fun setFavorite(id: Long, fav: Boolean)

    // Programme
    @Query("UPDATE schedule SET epochDay = :epochDay WHERE id = :id") suspend fun updateScheduleDay(id: Long, epochDay: Long)
    @Query("SELECT epochDay FROM schedule WHERE id = :id") suspend fun scheduleDay(id: Long): Long?

    @Transaction @Query("SELECT * FROM schedule WHERE id = :id")
    suspend fun scheduledItem(id: Long): ScheduledItem?

    @Transaction @Query("SELECT * FROM schedule WHERE epochDay = :day AND completed = 0 ORDER BY id LIMIT 1")
    suspend fun firstOpenOnDay(day: Long): ScheduledItem?
    @Transaction @Query("SELECT * FROM schedule WHERE epochDay >= :fromDay ORDER BY epochDay LIMIT :limit")
    fun observeUpcoming(fromDay: Long, limit: Int): Flow<List<ScheduledItem>>

    @Transaction @Query("SELECT * FROM schedule WHERE epochDay = :day ORDER BY id")
    fun observeDay(day: Long): Flow<List<ScheduledItem>>

    @Insert suspend fun insertSchedule(items: List<ScheduleEntity>)
    @Delete suspend fun deleteSchedule(item: ScheduleEntity)
    @Query("UPDATE schedule SET completed = 1 WHERE id = :id") suspend fun markDone(id: Long)

    // Programmes d'objectif
    @Insert suspend fun insertProgram(p: ProgramEntity): Long

    @Query(
        "SELECT g.*, " +
            "(SELECT COUNT(*) FROM schedule s JOIN plans p ON s.planId = p.id WHERE p.programId = g.id) AS total, " +
            "(SELECT COUNT(*) FROM schedule s JOIN plans p ON s.planId = p.id WHERE p.programId = g.id AND s.completed = 1) AS done " +
            "FROM programs g ORDER BY g.startEpochDay DESC",
    )
    fun observePrograms(): Flow<List<ProgramSummary>>

    @Query("DELETE FROM plans WHERE programId = :programId") suspend fun deletePlansOfProgram(programId: Long)
    @Query("SELECT * FROM programs WHERE id = :id") suspend fun getProgram(id: Long): ProgramEntity?
    @Update suspend fun updateProgramEntity(p: ProgramEntity)

    @Query("SELECT s.epochDay FROM schedule s JOIN plans p ON s.planId = p.id WHERE p.programId = :programId AND s.completed = 1")
    suspend fun completedDaysOfProgram(programId: Long): List<Long>

    /** Supprime les séances du programme pas encore faites (leurs entrées de calendrier partent en cascade). */
    @Query("DELETE FROM plans WHERE programId = :programId AND id NOT IN (SELECT planId FROM schedule WHERE completed = 1)")
    suspend fun deleteOpenPlansOfProgram(programId: Long)
    @Query("DELETE FROM programs WHERE id = :id") suspend fun deleteProgram(id: Long)

    @Query(
        "SELECT s.id AS scheduleId, s.epochDay AS epochDay, p.name AS planName, g.reminderMinutes AS reminderMinutes " +
            "FROM schedule s JOIN plans p ON s.planId = p.id JOIN programs g ON p.programId = g.id " +
            "WHERE g.reminderMinutes >= 0 AND s.completed = 0 AND s.epochDay >= :fromDay ORDER BY s.epochDay LIMIT 30",
    )
    suspend fun reminderRows(fromDay: Long): List<ReminderRow>

    @Query("SELECT s.completed FROM schedule s WHERE s.id = :id") suspend fun isCompleted(id: Long): Boolean?

    // Historique
    @Query("SELECT * FROM sessions ORDER BY startedAt DESC")
    fun observeSessions(): Flow<List<SessionEntity>>

    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun deleteSession(id: Long)
    @Query("UPDATE sessions SET exported = 1 WHERE id = :id") suspend fun markExported(id: Long)
}

@Database(
    entities = [PlanEntity::class, StepEntity::class, ScheduleEntity::class, SessionEntity::class, ProgramEntity::class],
    version = 5,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): TrainingDao

    companion object {
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plans ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE plans ADD COLUMN programId INTEGER")
                db.execSQL("ALTER TABLE steps ADD COLUMN targetZone INTEGER")
                db.execSQL("ALTER TABLE steps ADD COLUMN minSpeedKmh REAL")
                db.execSQL("ALTER TABLE steps ADD COLUMN maxSpeedKmh REAL")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS programs (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, goal TEXT NOT NULL, startEpochDay INTEGER NOT NULL, totalDays INTEGER NOT NULL, " +
                        "weekdaysMask INTEGER NOT NULL, reminderMinutes INTEGER NOT NULL, maxSpeedKmh REAL NOT NULL)",
                )
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN steps INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE sessions ADD COLUMN activeKcal INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE sessions ADD COLUMN exported INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
