package com.entrenadorlocal.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
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
    val equipment: String?,
    val createdAtMillis: Long
)

@Entity(tableName = "exercise_aliases", indices = [Index(value = ["normalizedAlias"], unique = true), Index("exerciseId")])
data class ExerciseAliasEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val exerciseId: Long,
    val normalizedAlias: String,
    val createdAtMillis: Long
)

@Entity(tableName = "workout_sessions")
data class WorkoutSessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val startedAtMillis: Long,
    val finishedAtMillis: Long? = null
)

@Entity(tableName = "workout_sets", indices = [Index("sessionId"), Index("exerciseId")])
data class WorkoutSetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val exerciseId: Long,
    val orderInExercise: Int,
    val loadGrams: Int,
    val repetitions: Int,
    val rir: Int? = null
)

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE id = 1 LIMIT 1")
    fun observe(): Flow<ProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(profile: ProfileEntity)
}

@Dao
interface WeighInDao {
    @Query("SELECT * FROM weigh_ins ORDER BY measuredAtMillis DESC, id DESC")
    fun observeAll(): Flow<List<WeighInEntity>>

    @Insert
    suspend fun insert(item: WeighInEntity)
}

@Database(
    entities = [ProfileEntity::class, WeighInEntity::class, ExerciseEntity::class,
        ExerciseAliasEntity::class, WorkoutSessionEntity::class, WorkoutSetEntity::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao
    abstract fun weighInDao(): WeighInDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext, AppDatabase::class.java, "entrenador_local.db"
            ).build().also { instance = it }
        }
    }
}
