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

@Entity(
    tableName = "workout_sessions",
    indices = [Index(value = ["sessionUuid"], unique = true)]
)
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null,
    val notes: String = "",
    val category: String = "",
    val sessionUuid: String? = null
)

@Entity(
    tableName = "workout_categories",
    indices = [Index(value = ["name"], unique = true)]
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String = "📁",
    val orderIndex: Int = 0,
    val isHidden: Boolean = false,
    val colorHex: String = "#75D9C1"
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
    val plannedTarget: String? = null,
    val loadUnit: String = "KG",
    val isToFailure: Boolean = false
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

@Entity(tableName = "irisx_conversations")
data class IrisxConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val activeMuscleGroup: String? = null,
    val activeExerciseName: String? = null,
    val activeSessionId: Long? = null,
    val activePeriodScope: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "irisx_messages",
    indices = [Index("conversationId")]
)
data class IrisxMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    val senderRole: String, // "USER" or "IRISX"
    val content: String,
    val intentType: String? = null,
    val referencedExercise: String? = null,
    val referencedMuscleGroup: String? = null,
    val referencedSessionId: Long? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
)

@Entity(tableName = "workout_routines")
data class RoutineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val targetCategory: String = "General",
    val createdAtMillis: Long = System.currentTimeMillis(),
    val updatedAtMillis: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "routine_exercises",
    indices = [Index("routineId"), Index("exerciseId")]
)
data class RoutineExerciseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val routineId: Long,
    val exerciseId: Long? = null,
    val exerciseName: String,
    val orderIndex: Int,
    val targetSets: Int,
    val minReps: Int,
    val maxReps: Int,
    val suggestedLoadGrams: Int? = null,
    val isDumbbell: Boolean = false,
    val isAssisted: Boolean = false,
    val notes: String = ""
)

@Entity(tableName = "active_workout_drafts")
data class ActiveWorkoutDraftEntity(
    @PrimaryKey val slotId: Int = 1,
    val sessionUuid: String,
    val state: String, // "IDLE", "ACTIVE", "REVIEW_PENDING", "COMPLETED", "CANCELLED", "ERROR"
    val title: String,
    val rawNotesText: String,
    val cursorPosition: Int = 0,
    val sourceRoutineId: Long? = null,
    val plannedCategory: String? = null,
    val startedAtWallMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val accumulatedPausedMillis: Long = 0L,
    val reviewStartedAtWallMillis: Long? = null,
    val reviewStartedAtElapsedMillis: Long? = null,
    val finalizedAtWallMillis: Long? = null,
    val lastSavedAtWallMillis: Long,
    val lastKnownElapsedRealtimeMillis: Long,
    val lastPersistedDurationMillis: Long = 0L,
    val committedSessionId: Long? = null,
    val lastErrorMessage: String? = null
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

    @Query("DELETE FROM exercise_aliases WHERE normalizedAlias = :normalizedAlias")
    suspend fun deleteByAlias(normalizedAlias: String)

    @Query("DELETE FROM exercise_aliases")
    suspend fun deleteAll()
}

@Dao
interface CategoryDao {
    @Query("SELECT * FROM workout_categories WHERE isHidden = 0 ORDER BY orderIndex ASC, name ASC")
    fun observeAllActive(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM workout_categories ORDER BY orderIndex ASC, name ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM workout_categories ORDER BY orderIndex ASC, name ASC")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM workout_categories WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("SELECT * FROM workout_categories WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): CategoryEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>)

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("DELETE FROM workout_categories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM workout_categories")
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

    @Query("SELECT * FROM workout_sessions WHERE sessionUuid = :uuid AND sessionUuid IS NOT NULL AND sessionUuid != '' LIMIT 1")
    suspend fun findBySessionUuid(uuid: String): WorkoutSessionEntity?

    @Insert
    suspend fun insert(session: WorkoutSessionEntity): Long

    @Insert
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

    @Query("SELECT * FROM workout_sets")
    fun observeAll(): Flow<List<WorkoutSetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(set: WorkoutSetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(sets: List<WorkoutSetEntity>)

    @Update
    suspend fun update(set: WorkoutSetEntity)

    @Delete
    suspend fun delete(set: WorkoutSetEntity)

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

@Dao
interface IrisxConversationDao {
    @Query("SELECT * FROM irisx_conversations ORDER BY updatedAtMillis DESC, id DESC")
    fun observeAllConversations(): Flow<List<IrisxConversationEntity>>

    @Query("SELECT * FROM irisx_conversations ORDER BY updatedAtMillis DESC, id DESC")
    suspend fun getAllConversations(): List<IrisxConversationEntity>

    @Query("SELECT * FROM irisx_conversations WHERE id = :id LIMIT 1")
    suspend fun getConversationById(id: Long): IrisxConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertConversation(conversation: IrisxConversationEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllConversations(conversations: List<IrisxConversationEntity>)

    @Update
    suspend fun updateConversation(conversation: IrisxConversationEntity)

    @Query("DELETE FROM irisx_conversations WHERE id = :id")
    suspend fun deleteConversationById(id: Long)

    @Query("DELETE FROM irisx_conversations")
    suspend fun deleteAllConversations()

    @Query("SELECT * FROM irisx_messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC, id ASC")
    fun observeMessagesForConversation(conversationId: Long): Flow<List<IrisxMessageEntity>>

    @Query("SELECT * FROM irisx_messages WHERE conversationId = :conversationId ORDER BY createdAtMillis ASC, id ASC")
    suspend fun getMessagesForConversation(conversationId: Long): List<IrisxMessageEntity>

    @Query("SELECT * FROM irisx_messages ORDER BY createdAtMillis ASC, id ASC")
    suspend fun getAllMessages(): List<IrisxMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: IrisxMessageEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllMessages(messages: List<IrisxMessageEntity>)

    @Query("DELETE FROM irisx_messages WHERE conversationId = :conversationId")
    suspend fun deleteMessagesForConversation(conversationId: Long)

    @Query("DELETE FROM irisx_messages")
    suspend fun deleteAllMessages()
}

@Dao
interface RoutineDao {
    @Query("SELECT * FROM workout_routines ORDER BY updatedAtMillis DESC, id DESC")
    fun observeAllRoutines(): Flow<List<RoutineEntity>>

    @Query("SELECT * FROM workout_routines ORDER BY updatedAtMillis DESC, id DESC")
    suspend fun getAllRoutines(): List<RoutineEntity>

    @Query("SELECT * FROM workout_routines WHERE id = :id LIMIT 1")
    suspend fun getRoutineById(id: Long): RoutineEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutine(routine: RoutineEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRoutines(routines: List<RoutineEntity>)

    @Update
    suspend fun updateRoutine(routine: RoutineEntity)

    @Query("DELETE FROM workout_routines WHERE id = :id")
    suspend fun deleteRoutineById(id: Long)

    @Query("DELETE FROM workout_routines")
    suspend fun deleteAllRoutines()

    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY orderIndex ASC, id ASC")
    fun observeExercisesForRoutine(routineId: Long): Flow<List<RoutineExerciseEntity>>

    @Query("SELECT * FROM routine_exercises WHERE routineId = :routineId ORDER BY orderIndex ASC, id ASC")
    suspend fun getExercisesForRoutine(routineId: Long): List<RoutineExerciseEntity>

    @Query("SELECT * FROM routine_exercises ORDER BY routineId ASC, orderIndex ASC, id ASC")
    fun observeAllRoutineExercises(): Flow<List<RoutineExerciseEntity>>

    @Query("SELECT * FROM routine_exercises ORDER BY routineId ASC, orderIndex ASC, id ASC")
    suspend fun getAllRoutineExercises(): List<RoutineExerciseEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRoutineExercise(item: RoutineExerciseEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllRoutineExercises(items: List<RoutineExerciseEntity>)

    @Query("DELETE FROM routine_exercises WHERE routineId = :routineId")
    suspend fun deleteExercisesForRoutine(routineId: Long)

    @Query("DELETE FROM routine_exercises")
    suspend fun deleteAllRoutineExercises()
}

@Dao
interface ActiveWorkoutDraftDao {
    @Query("SELECT * FROM active_workout_drafts WHERE slotId = 1 LIMIT 1")
    fun observeCurrentDraft(): Flow<ActiveWorkoutDraftEntity?>

    @Query("SELECT * FROM active_workout_drafts WHERE slotId = 1 LIMIT 1")
    suspend fun getCurrentDraft(): ActiveWorkoutDraftEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDraft(draft: ActiveWorkoutDraftEntity)

    @Query("DELETE FROM active_workout_drafts WHERE slotId = 1")
    suspend fun clearCurrentDraft()

    @Query("DELETE FROM active_workout_drafts WHERE slotId = :slotId AND sessionUuid = :uuid")
    suspend fun clearDraftBySlotAndUuid(slotId: Int, uuid: String)

    @Query("DELETE FROM active_workout_drafts")
    suspend fun deleteAllDrafts()
}

@Database(
    entities = [
        ProfileEntity::class,
        WeighInEntity::class,
        ExerciseEntity::class,
        ExerciseAliasEntity::class,
        WorkoutSessionEntity::class,
        WorkoutSetEntity::class,
        IrisxMemoryEntity::class,
        IrisxConversationEntity::class,
        IrisxMessageEntity::class,
        RoutineEntity::class,
        RoutineExerciseEntity::class,
        ActiveWorkoutDraftEntity::class,
        CategoryEntity::class
    ],
    version = 8,
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
    abstract fun irisxConversationDao(): IrisxConversationDao
    abstract fun routineDao(): RoutineDao
    abstract fun activeWorkoutDraftDao(): ActiveWorkoutDraftDao
    abstract fun categoryDao(): CategoryDao

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

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS irisx_conversations (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        title TEXT NOT NULL,
                        activeMuscleGroup TEXT,
                        activeExerciseName TEXT,
                        activeSessionId INTEGER,
                        activePeriodScope TEXT,
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS irisx_messages (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        conversationId INTEGER NOT NULL,
                        senderRole TEXT NOT NULL,
                        content TEXT NOT NULL,
                        intentType TEXT,
                        referencedExercise TEXT,
                        referencedMuscleGroup TEXT,
                        referencedSessionId INTEGER,
                        createdAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_irisx_messages_conversationId ON irisx_messages (conversationId)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workout_routines (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        description TEXT NOT NULL DEFAULT '',
                        targetCategory TEXT NOT NULL DEFAULT 'General',
                        createdAtMillis INTEGER NOT NULL,
                        updatedAtMillis INTEGER NOT NULL
                    )
                """.trimIndent())

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS routine_exercises (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        routineId INTEGER NOT NULL,
                        exerciseId INTEGER,
                        exerciseName TEXT NOT NULL,
                        orderIndex INTEGER NOT NULL,
                        targetSets INTEGER NOT NULL,
                        minReps INTEGER NOT NULL,
                        maxReps INTEGER NOT NULL,
                        suggestedLoadGrams INTEGER,
                        isDumbbell INTEGER NOT NULL DEFAULT 0,
                        isAssisted INTEGER NOT NULL DEFAULT 0,
                        notes TEXT NOT NULL DEFAULT ''
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_routine_exercises_routineId ON routine_exercises (routineId)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_routine_exercises_exerciseId ON routine_exercises (exerciseId)")
            }
        }

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS active_workout_drafts (
                        slotId INTEGER PRIMARY KEY NOT NULL,
                        sessionUuid TEXT NOT NULL,
                        state TEXT NOT NULL,
                        title TEXT NOT NULL,
                        rawNotesText TEXT NOT NULL,
                        cursorPosition INTEGER NOT NULL DEFAULT 0,
                        sourceRoutineId INTEGER,
                        plannedCategory TEXT,
                        startedAtWallMillis INTEGER NOT NULL,
                        startedAtElapsedRealtimeMillis INTEGER NOT NULL,
                        accumulatedPausedMillis INTEGER NOT NULL DEFAULT 0,
                        reviewStartedAtWallMillis INTEGER,
                        reviewStartedAtElapsedMillis INTEGER,
                        finalizedAtWallMillis INTEGER,
                        lastSavedAtWallMillis INTEGER NOT NULL,
                        lastKnownElapsedRealtimeMillis INTEGER NOT NULL,
                        lastPersistedDurationMillis INTEGER NOT NULL DEFAULT 0,
                        committedSessionId INTEGER,
                        lastErrorMessage TEXT
                    )
                """.trimIndent())
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN category TEXT NOT NULL DEFAULT ''")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS workout_categories (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        icon TEXT NOT NULL DEFAULT '📁',
                        orderIndex INTEGER NOT NULL DEFAULT 0,
                        isHidden INTEGER NOT NULL DEFAULT 0,
                        colorHex TEXT NOT NULL DEFAULT '#75D9C1'
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_workout_categories_name ON workout_categories (name)")

                val defaultCategories = listOf(
                    Triple("Pecho", "🛡️", 0),
                    Triple("Espalda", "🏹", 1),
                    Triple("Piernas", "🦵", 2),
                    Triple("Hombros", "🏋️", 3),
                    Triple("Brazos", "💪", 4),
                    Triple("Full Body", "⚡", 5),
                    Triple("Torso", "🏛️", 6),
                    Triple("Push", "↗️", 7),
                    Triple("Pull", "↙️", 8)
                )
                for ((name, icon, order) in defaultCategories) {
                    db.execSQL(
                        "INSERT OR IGNORE INTO workout_categories (name, icon, orderIndex, isHidden, colorHex) VALUES ('$name', '$icon', $order, 0, '#75D9C1')"
                    )
                }

                db.execSQL("UPDATE workout_sessions SET category = 'Pecho' WHERE category = '' AND (title LIKE '%Pecho%' OR title LIKE '%Chest%')")
                db.execSQL("UPDATE workout_sessions SET category = 'Espalda' WHERE category = '' AND (title LIKE '%Espalda%' OR title LIKE '%Back%')")
                db.execSQL("UPDATE workout_sessions SET category = 'Piernas' WHERE category = '' AND (title LIKE '%Pierna%' OR title LIKE '%Leg%')")
                db.execSQL("UPDATE workout_sessions SET category = 'Hombros' WHERE category = '' AND (title LIKE '%Hombro%' OR title LIKE '%Shoulder%')")
                db.execSQL("UPDATE workout_sessions SET category = 'Brazos' WHERE category = '' AND (title LIKE '%Brazo%' OR title LIKE '%Arm%' OR title LIKE '%Bícep%' OR title LIKE '%Trícep%')")
                db.execSQL("UPDATE workout_sessions SET category = 'Full Body' WHERE category = '' AND (title LIKE '%Full Body%' OR title LIKE '%Cuerpo Completo%')")
                db.execSQL("UPDATE workout_sessions SET category = 'Torso' WHERE category = '' AND title LIKE '%Torso%'")
                db.execSQL("UPDATE workout_sessions SET category = 'Push' WHERE category = '' AND title LIKE '%Push%'")
                db.execSQL("UPDATE workout_sessions SET category = 'Pull' WHERE category = '' AND title LIKE '%Pull%'")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN loadUnit TEXT NOT NULL DEFAULT 'KG'")
                db.execSQL("ALTER TABLE workout_sets ADD COLUMN isToFailure INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE workout_sessions ADD COLUMN sessionUuid TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_workout_sessions_sessionUuid ON workout_sessions (sessionUuid)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Table recreation migration to make sessionUuid nullable and enforce UNIQUE constraint
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `workout_sessions_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `title` TEXT NOT NULL,
                        `startedAtMillis` INTEGER NOT NULL,
                        `finishedAtMillis` INTEGER,
                        `notes` TEXT NOT NULL,
                        `category` TEXT NOT NULL,
                        `sessionUuid` TEXT
                    )
                """.trimIndent())

                // Deterministic UUID assignment policy:
                // 1. Normalize: trim leading/trailing whitespace. Empty or blank strings map to NULL (legacy sessions).
                // 2. Reserved originals: record all existing non-blank normalized UUIDs before generating replacements.
                // 3. First-come reservation: the first session (ordered by id ASC) in any duplicate group retains the normalized UUID.
                // 4. Collision-free substitutes: duplicates generate candidate "${trimmed}_dup_${id}".
                //    If the candidate collides with ANY reserved original UUID or already-assigned UUID, append a progressive counter.
                // 5. Preserves all row IDs, sessions, sets, and foreign key relations without drops or cascades.
                val reservedOriginalUuids = mutableSetOf<String>()
                data class SessionRow(
                    val id: Long,
                    val title: String,
                    val startedAtMillis: Long,
                    val finishedAtMillis: Long?,
                    val notes: String,
                    val category: String,
                    val trimmedUuid: String?
                )
                val rows = mutableListOf<SessionRow>()

                db.query("SELECT id, title, startedAtMillis, finishedAtMillis, notes, category, sessionUuid FROM workout_sessions ORDER BY id ASC").use { cursor ->
                    val idIdx = cursor.getColumnIndexOrThrow("id")
                    val titleIdx = cursor.getColumnIndexOrThrow("title")
                    val startedIdx = cursor.getColumnIndexOrThrow("startedAtMillis")
                    val finishedIdx = cursor.getColumnIndexOrThrow("finishedAtMillis")
                    val notesIdx = cursor.getColumnIndexOrThrow("notes")
                    val catIdx = cursor.getColumnIndexOrThrow("category")
                    val uuidIdx = cursor.getColumnIndexOrThrow("sessionUuid")

                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(idIdx)
                        val title = cursor.getString(titleIdx)
                        val started = cursor.getLong(startedIdx)
                        val finished = if (cursor.isNull(finishedIdx)) null else cursor.getLong(finishedIdx)
                        val notes = cursor.getString(notesIdx)
                        val cat = cursor.getString(catIdx)
                        val rawUuid = if (cursor.isNull(uuidIdx)) null else cursor.getString(uuidIdx)
                        val trimmed = rawUuid?.trim()?.takeIf { it.isNotEmpty() }
                        if (trimmed != null) {
                            reservedOriginalUuids.add(trimmed)
                        }
                        rows.add(SessionRow(id, title, started, finished, notes, cat, trimmed))
                    }
                }

                val assignedUuids = mutableSetOf<String>()
                val insertStmt = db.compileStatement(
                    "INSERT INTO `workout_sessions_new` (`id`, `title`, `startedAtMillis`, `finishedAtMillis`, `notes`, `category`, `sessionUuid`) VALUES (?, ?, ?, ?, ?, ?, ?)"
                )

                for (row in rows) {
                    val finalUuid: String? = if (row.trimmedUuid == null) {
                        null
                    } else if (!assignedUuids.contains(row.trimmedUuid)) {
                        assignedUuids.add(row.trimmedUuid)
                        row.trimmedUuid
                    } else {
                        var candidate = "${row.trimmedUuid}_dup_${row.id}"
                        var counter = 1
                        while (assignedUuids.contains(candidate) || reservedOriginalUuids.contains(candidate)) {
                            candidate = "${row.trimmedUuid}_dup_${row.id}_$counter"
                            counter++
                        }
                        assignedUuids.add(candidate)
                        candidate
                    }

                    insertStmt.bindLong(1, row.id)
                    insertStmt.bindString(2, row.title)
                    insertStmt.bindLong(3, row.startedAtMillis)
                    if (row.finishedAtMillis != null) {
                        insertStmt.bindLong(4, row.finishedAtMillis)
                    } else {
                        insertStmt.bindNull(4)
                    }
                    insertStmt.bindString(5, row.notes)
                    insertStmt.bindString(6, row.category)
                    if (finalUuid != null) {
                        insertStmt.bindString(7, finalUuid)
                    } else {
                        insertStmt.bindNull(7)
                    }
                    insertStmt.executeInsert()
                }

                db.execSQL("DROP TABLE `workout_sessions`")
                db.execSQL("ALTER TABLE `workout_sessions_new` RENAME TO `workout_sessions`")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workout_sessions_sessionUuid` ON `workout_sessions` (`sessionUuid`)")
            }
        }

        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "syntra.db"
            ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
            .build().also { instance = it }
        }
    }
}
