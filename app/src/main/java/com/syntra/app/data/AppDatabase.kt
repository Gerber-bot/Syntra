package com.syntra.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey val id: Int = 1,
    val name: String,
    val preferredName: String,
    val heightCm: Int,
    val initialWeightGrams: Int,
    val activityLevel: String,
    val goal: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long
)

@Entity(tableName = "weigh_ins")
data class WeighInEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val measuredAtMillis: Long,
    val weightGrams: Int,
    val note: String = ""
)

@Entity(tableName = "exercises", indices = [Index(value = ["normalizedName"], unique = true)])
data class ExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val displayName: String,
    val normalizedName: String,
    val muscleGroup: String,
    val secondaryMuscleGroups: String = "",
    val equipment: String? = null,
    val exerciseType: String = "strength",
    val status: String = "CONFIRMED",
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "exercise_aliases", indices = [Index(value = ["normalizedAlias"], unique = true), Index("exerciseId")])
data class ExerciseAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val normalizedAlias: String,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val notes: String = ""
)

@Entity(tableName = "workout_sets", indices = [Index("sessionId"), Index("exerciseId")])
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val orderInExercise: Int,
    val executionOrder: Int = 0,
    val loadGrams: Int,
    val repetitions: Int,
    val rir: Int? = null,
    val isAssisted: Boolean = false,
    val isDumbbell: Boolean = false,
    val note: String = "",
    val plannedTarget: String? = null
)

@Entity(
    tableName = "irisx_memories",
    indices = [
        Index("memoryType"),
        Index(value = ["sourceKey", "memoryType"], unique = true)
    ]
)
data class IrisxMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val memoryType: String,
    val sourceKey: String,
    val targetValue: String,
    val confidence: Double = 1.0,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE id = 1 LIMIT 1")
    fun observe(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE id = 1 LIMIT 1")
    suspend fun get(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: ProfileEntity)

    @Query("DELETE FROM profiles")
    suspend fun deleteAll()
}

@Dao
interface WeighInDao {
    @Query("SELECT * FROM weigh_ins ORDER BY measuredAtMillis DESC, id DESC")
    fun observeAll(): Flow<List<WeighInEntity>>

    @Query("SELECT * FROM weigh_ins ORDER BY measuredAtMillis DESC, id DESC")
    suspend fun getAll(): List<WeighInEntity>

    @Insert
    suspend fun insert(item: WeighInEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<WeighInEntity>)

    @Query("DELETE FROM weigh_ins")
    suspend fun deleteAll()
}

@Dao
interface ExerciseDao {
    @Query("SELECT * FROM exercises ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ExerciseEntity>>

    @Query("SELECT * FROM exercises ORDER BY displayName ASC")
    suspend fun getAll(): List<ExerciseEntity>

    @Query("SELECT * FROM exercises WHERE normalizedName = :normalized LIMIT 1")
    suspend fun findByNormalizedName(normalized: String): ExerciseEntity?

    @Query("SELECT * FROM exercises WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): ExerciseEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(exercise: ExerciseEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(exercises: List<ExerciseEntity>)

    @Update
    suspend fun update(exercise: ExerciseEntity)

    @Query("DELETE FROM exercises")
    suspend fun deleteAll()
}

@Dao
interface ExerciseAliasDao {
    @Query("SELECT * FROM exercise_aliases WHERE exerciseId = :exerciseId")
    fun observeForExercise(exerciseId: Long): Flow<List<ExerciseAliasEntity>>

    @Query("SELECT * FROM exercise_aliases")
    suspend fun getAll(): List<ExerciseAliasEntity>

    @Query("SELECT * FROM exercise_aliases WHERE normalizedAlias = :normalizedAlias LIMIT 1")
    suspend fun findByAlias(normalizedAlias: String): ExerciseAliasEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(alias: ExerciseAliasEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(aliases: List<ExerciseAliasEntity>)

    @Query("DELETE FROM exercise_aliases")
    suspend fun deleteAll()
}

@Dao
interface WorkoutSessionDao {
    @Query("SELECT * FROM workout_sessions ORDER BY startedAtMillis DESC, id DESC")
    fun observeAll(): Flow<List<WorkoutSessionEntity>>

    @Query("SELECT * FROM workout_sessions ORDER BY startedAtMillis DESC, id DESC")
    suspend fun getAll(): List<WorkoutSessionEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): WorkoutSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: WorkoutSessionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sessions: List<WorkoutSessionEntity>)

    @Update
    suspend fun update(session: WorkoutSessionEntity)

    @Delete
    suspend fun delete(session: WorkoutSessionEntity)

    @Query("DELETE FROM workout_sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM workout_sessions")
    suspend fun deleteAll()
}

@Dao
interface WorkoutSetDao {
    @Query("SELECT * FROM workout_sets WHERE sessionId = :sessionId ORDER BY executionOrder ASC, orderInExercise ASC, id ASC")
    fun observeForSession(sessionId: Long): Flow<List<WorkoutSetEntity>>

    @Query("SELECT * FROM workout_sets WHERE sessionId = :sessionId ORDER BY executionOrder ASC, orderInExercise ASC, id ASC")
    suspend fun getForSession(sessionId: Long): List<WorkoutSetEntity>

    @Query("SELECT * FROM workout_sets")
    suspend fun getAll(): List<WorkoutSetEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(set: WorkoutSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sets: List<WorkoutSetEntity>)

    @Query("DELETE FROM workout_sets WHERE sessionId = :sessionId")
    suspend fun deleteForSession(sessionId: Long)

    @Query("DELETE FROM workout_sets")
    suspend fun deleteAll()
}

@Dao
interface IrisxMemoryDao {
    @Query("SELECT * FROM irisx_memories ORDER BY memoryType ASC, sourceKey ASC")
    fun observeAll(): Flow<List<IrisxMemoryEntity>>

    @Query("SELECT * FROM irisx_memories ORDER BY memoryType ASC, sourceKey ASC")
    suspend fun getAll(): List<IrisxMemoryEntity>

    @Query("SELECT * FROM irisx_memories WHERE memoryType = :type")
    suspend fun findByType(type: String): List<IrisxMemoryEntity>

    @Query("SELECT * FROM irisx_memories WHERE sourceKey = :key AND memoryType = :type LIMIT 1")
    suspend fun findByTypeAndKey(type: String, key: String): IrisxMemoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: IrisxMemoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(memories: List<IrisxMemoryEntity>)

    @Update
    suspend fun update(memory: IrisxMemoryEntity)

    @Delete
    suspend fun delete(memory: IrisxMemoryEntity)

    @Query("DELETE FROM irisx_memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM irisx_memories")
    suspend fun deleteAll()
}

@Database(
    entities = [
        ProfileEntity::class,
        WeighInEntity::class,
        ExerciseEntity::class,
        ExerciseAliasEntity::class,
        WorkoutSessionEntity::class,
        WorkoutSetEntity::class,
        IrisxMemoryEntity::class
    ],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun weighInDao(): WeighInDao
    abstract fun exerciseDao(): ExerciseDao
    abstract fun exerciseAliasDao(): ExerciseAliasDao
    abstract fun workoutSessionDao(): WorkoutSessionDao
    abstract fun workoutSetDao(): WorkoutSetDao
    abstract fun irisxMemoryDao(): IrisxMemoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE exercises ADD COLUMN secondaryMuscleGroups TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE exercises ADD COLUMN exerciseType TEXT NOT NULL DEFAULT 'strength'")
                db.execSQL("ALTER TABLE exercises ADD COLUMN status TEXT NOT NULL DEFAULT 'CONFIRMED'")

                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN notes TEXT NOT NULL DEFAULT ''")

                db.execSQL("ALTER TABLE workout_sets ADD COLUMN executionOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN isAssisted INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN isDumbbell INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN note TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN plannedTarget TEXT")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS irisx_memories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        memoryType TEXT NOT NULL,
                        sourceKey TEXT NOT NULL,
                        targetValue TEXT NOT NULL,
                        confidence REAL NOT NULL DEFAULT 1.0,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_irisx_memories_memoryType ON irisx_memories (memoryType)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_irisx_memories_sourceKey_memoryType ON irisx_memories (sourceKey, memoryType)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "syntra.db"
            ).addMigrations(MIGRATION_1_2)
            .build().also { instance = it }
        }
    }
}
