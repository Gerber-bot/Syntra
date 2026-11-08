package com.syntra.app.domain.irisx

import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseAliasEntity
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxMemoryEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.ExerciseCatalog
import java.text.DateFormat
import java.text.Normalizer
import java.util.Date
import java.util.Locale

interface IrisXRepository {
    suspend fun getLatestSession(): WorkoutSessionEntity?
    suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity>
    suspend fun getExerciseNameMap(): Map<Long, String>
    suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity?
    suspend fun updateExercise(exercise: ExerciseEntity)
    suspend fun getLearnedAliases(): Map<String, String>
    suspend fun saveLearnedAlias(alias: String, canonical: String)
    suspend fun deleteLearnedAlias(alias: String)
    suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String)
}

class RoomIrisXRepository(private val db: AppDatabase) : IrisXRepository {
    override suspend fun getLatestSession(): WorkoutSessionEntity? {
        val sessions = db.workoutSessionDao().getAll()
        return sessions.maxByOrNull { it.startedAtMillis }
    }

    override suspend fun getSetsForSession(sessionId: Long): List<WorkoutSetEntity> {
        return db.workoutSetDao().getForSession(sessionId)
    }

    override suspend fun getExerciseNameMap(): Map<Long, String> {
        return db.exerciseDao().getAll().associate { it.id to it.displayName }
    }

    override suspend fun findExerciseByNormalized(normalized: String): ExerciseEntity? {
        return db.exerciseDao().findByNormalizedName(normalized)
    }

    override suspend fun updateExercise(exercise: ExerciseEntity) {
        db.exerciseDao().update(exercise)
    }

    override suspend fun getLearnedAliases(): Map<String, String> {
        val memories = db.irisxMemoryDao().findByType("ALIAS")
        return memories.associate { it.sourceKey to it.targetValue }
    }

    override suspend fun saveLearnedAlias(alias: String, canonical: String) {
        val normAlias = Normalizer.normalize(alias.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normCanonical = Normalizer.normalize(canonical.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val existing = db.irisxMemoryDao().findByTypeAndKey("ALIAS", normAlias)
        val now = System.currentTimeMillis()
        val memory = IrisxMemoryEntity(
            id = existing?.id ?: 0,
            memoryType = "ALIAS",
            sourceKey = normAlias,
            targetValue = canonical.trim(),
            confidence = 1.0,
            createdAtMillis = existing?.createdAtMillis ?: now,
            updatedAtMillis = now
        )
        db.irisxMemoryDao().insert(memory)

        val exercise = db.exerciseDao().findByNormalizedName(normCanonical)
        if (exercise != null) {
            db.exerciseAliasDao().insert(
                ExerciseAliasEntity(
                    exerciseId = exercise.id,
                    normalizedAlias = normAlias
                )
            )
        }
    }

    override suspend fun deleteLearnedAlias(alias: String) {
        val normAlias = Normalizer.normalize(alias.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val existing = db.irisxMemoryDao().findByTypeAndKey("ALIAS", normAlias)
        if (existing != null) {
            db.irisxMemoryDao().deleteById(existing.id)
        }
    }

    override suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String) {
        val existing = db.irisxMemoryDao().findByTypeAndKey(memoryType, sourceKey)
        val now = System.currentTimeMillis()
        db.irisxMemoryDao().insert(
            IrisxMemoryEntity(
                id = existing?.id ?: 0,
                memoryType = memoryType,
                sourceKey = sourceKey,
                targetValue = targetValue,
                confidence = 1.0,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now
            )
        )
    }
}

class IrisXCore(
    private val repository: IrisXRepository
) {

    private fun normalize(text: String): String {
        return Normalizer.normalize(text.trim().lowercase(Locale.ROOT), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Parses a raw text message from the IrisX interaction console into an IrisXIntent.
     */
    fun parseInput(input: String): IrisXIntent {
        val trimmed = input.trim()
        val norm = normalize(trimmed)

        if (norm.isBlank()) {
            return IrisXIntent.GeneralQuery("")
        }

        // 1. Query last workout
        if (norm.contains("ultimo entreno") || norm.contains("ultima sesion") ||
            norm.contains("ultimo entrenamiento") || norm == "resumen" || norm == "ultimo"
        ) {
            return IrisXIntent.QueryLastWorkout
        }

        // 2. Confirm forced alias overwrite: "confirmar alias X = Y"
        val confirmAliasRegex = Regex("^confirmar\\s+alias\\s+(.+?)\\s*(?:=|es|->)\\s*(.+)$", RegexOption.IGNORE_CASE)
        val confirmAliasMatch = confirmAliasRegex.find(trimmed)
        if (confirmAliasMatch != null) {
            val (alias, canonical) = confirmAliasMatch.destructured
            return IrisXIntent.LearnAlias(alias.trim(), canonical.trim(), force = true)
        }

        // 3. Delete alias: "eliminar alias X" or "borrar alias X"
        val deleteAliasRegex = Regex("^(?:eliminar|borrar)\\s+alias\\s+(.+)$", RegexOption.IGNORE_CASE)
        val deleteAliasMatch = deleteAliasRegex.find(trimmed)
        if (deleteAliasMatch != null) {
            val alias = deleteAliasMatch.groupValues[1].trim()
            return IrisXIntent.DeleteAlias(alias)
        }

        // 4. Learn alias: e.g. "alias camilla adelante = extension de cuadriceps" or "aprender alias X es Y"
        val aliasRegex = Regex("^(?:aprender\\s+)?alias\\s+(.+?)\\s*(?:=|es|->)\\s*(.+)$", RegexOption.IGNORE_CASE)
        val aliasMatch = aliasRegex.find(trimmed)
        if (aliasMatch != null) {
            val (alias, canonical) = aliasMatch.destructured
            return IrisXIntent.LearnAlias(alias.trim(), canonical.trim(), force = false)
        }

        // 5. Update exercise muscle group: e.g. "musculo sentadilla = piernas" or "grupo sentadilla es piernas"
        val muscleRegex = Regex("^(?:musculo|grupo)\\s+(.+?)\\s*(?:=|es|->)\\s*(.+)$", RegexOption.IGNORE_CASE)
        val muscleMatch = muscleRegex.find(trimmed)
        if (muscleMatch != null) {
            val (exName, group) = muscleMatch.destructured
            return IrisXIntent.UpdateExerciseMuscle(exName.trim(), group.trim())
        }

        // 6. Query exercise info: "ejercicio sentadilla" or "info sentadilla" or "buscar sentadilla"
        val exerciseQueryRegex = Regex("^(?:ejercicio|info|buscar)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val exerciseMatch = exerciseQueryRegex.find(trimmed)
        if (exerciseMatch != null) {
            val exName = exerciseMatch.groupValues[1].trim()
            return IrisXIntent.QueryExercise(exName)
        }

        // 7. Classify session text: "clasificar [ejercicios separados por comas o saltos]"
        val classifyRegex = Regex("^clasificar(?:\\s+sesion)?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val classifyMatch = classifyRegex.find(trimmed)
        if (classifyMatch != null) {
            val body = classifyMatch.groupValues[1]
            val lines = body.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotBlank() }
            return IrisXIntent.ClassifySession(sessionTitle = "Sesión IrisX", exerciseNames = lines)
        }

        return IrisXIntent.GeneralQuery(trimmed)
    }

    /**
     * Executes an IrisXIntent deterministically using repository and domain rules.
     */
    suspend fun execute(intent: IrisXIntent): IrisXResponse {
        return when (intent) {
            is IrisXIntent.QueryLastWorkout -> handleQueryLastWorkout()
            is IrisXIntent.LearnAlias -> handleLearnAlias(intent.alias, intent.canonical, intent.force)
            is IrisXIntent.DeleteAlias -> handleDeleteAlias(intent.alias)
            is IrisXIntent.UpdateExerciseMuscle -> handleUpdateExerciseMuscle(intent.exerciseName, intent.muscleGroup)
            is IrisXIntent.QueryExercise -> handleQueryExercise(intent.exerciseName)
            is IrisXIntent.ClassifySession -> handleClassifySession(intent.sessionTitle, intent.exerciseNames)
            is IrisXIntent.GeneralQuery -> handleGeneralQuery(intent.text)
        }
    }

    suspend fun getLearnedAliases(): Map<String, String> {
        return repository.getLearnedAliases()
    }

    private suspend fun handleQueryLastWorkout(): IrisXResponse {
        val latest = repository.getLatestSession()
            ?: return IrisXResponse.Success("No tenés entrenamientos registrados todavía.")

        val sets = repository.getSetsForSession(latest.id)
        val exercises = repository.getExerciseNameMap()
        val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(latest.startedAtMillis))

        val exerciseNames = sets.map { exercises[it.exerciseId] ?: "Ejercicio #${it.exerciseId}" }.distinct()
        val learnedAliases = getLearnedAliases()
        val classification = IrisXClassifier.classifyWorkout(latest.title, exerciseNames, learnedAliases)
        val metrics = com.syntra.app.domain.SportsMetricsCalculator.calculateSessionVolume(sets)

        val summaryText = buildString {
            append("Última sesión: ${latest.title} ($dateStr)\n")
            append("Categoría: ${classification.primaryCategory.displayName} (${classification.sessionType})\n")
            append("Series totales: ${sets.size} | Carga externa: ${String.format(Locale.ROOT, "%.1f", metrics.totalExternalKg)} kg\n")
            if (metrics.dumbbellSetsCount > 0 || metrics.assistedSetsCount > 0) {
                append("(${metrics.regularSetsCount} series barra/máq., ${metrics.dumbbellSetsCount} mancuernas [c/u], ${metrics.assistedSetsCount} asistidas)\n")
            }
            append("Ejercicios:\n")
            exerciseNames.forEach { ex ->
                append("• $ex\n")
            }
        }

        return IrisXResponse.Success(
            message = summaryText.trim(),
            actionTaken = "QUERY_LAST_WORKOUT",
            payload = latest
        )
    }

    private suspend fun handleLearnAlias(alias: String, canonical: String, force: Boolean): IrisXResponse {
        val normAlias = normalize(alias)
        val normCanonical = normalize(canonical)

        if (normAlias.isBlank() || normCanonical.isBlank()) {
            return IrisXResponse.Unrecognized("El alias o el ejercicio de destino no pueden estar vacíos.")
        }

        val existingAliases = repository.getLearnedAliases()
        val previousCanonical = existingAliases[normAlias]

        if (previousCanonical != null && previousCanonical.equals(canonical, ignoreCase = true)) {
            return IrisXResponse.Success("Ya tenía registrado que '$alias' es '$canonical'.")
        }

        // Conflict check: existing alias points to a different canonical and wasn't confirmed with force
        if (previousCanonical != null && !previousCanonical.equals(canonical, ignoreCase = true) && !force) {
            return IrisXResponse.RequiresConfirmation(
                question = "El alias '$alias' ya estaba asignado a '$previousCanonical'. ¿Deseás sobrescribirlo con '$canonical'?",
                confirmCommand = "confirmar alias $alias = $canonical"
            )
        }

        repository.saveLearnedAlias(normAlias, canonical.trim())

        return IrisXResponse.Success(
            message = "He aprendido que '$alias' corresponde a '$canonical'. Las próximas sesiones se reconocerán automáticamente.",
            actionTaken = "LEARN_ALIAS"
        )
    }

    private suspend fun handleDeleteAlias(alias: String): IrisXResponse {
        val normAlias = normalize(alias)
        val existingAliases = repository.getLearnedAliases()
        if (existingAliases[normAlias] == null) {
            return IrisXResponse.Unrecognized("No tenía ningún alias registrado para '$alias'.")
        }

        repository.deleteLearnedAlias(normAlias)
        return IrisXResponse.Success(
            message = "He eliminado la equivalencia para '$alias'.",
            actionTaken = "DELETE_ALIAS"
        )
    }

    private suspend fun handleUpdateExerciseMuscle(exerciseName: String, muscleGroup: String): IrisXResponse {
        val normName = normalize(exerciseName)
        val exercise = repository.findExerciseByNormalized(normName)
            ?: return IrisXResponse.Unrecognized("No encontré el ejercicio '$exerciseName' en la biblioteca.")

        val updated = exercise.copy(muscleGroup = muscleGroup.trim())
        repository.updateExercise(updated)
        repository.saveMemory("CLASSIFICATION_OVERRIDE", normName, muscleGroup.trim())

        return IrisXResponse.Success(
            message = "Actualicé el grupo muscular de '${exercise.displayName}' a '$muscleGroup'.",
            actionTaken = "UPDATE_EXERCISE_MUSCLE",
            payload = updated
        )
    }

    private suspend fun handleQueryExercise(exerciseName: String): IrisXResponse {
        val normName = normalize(exerciseName)
        val dbExercise = repository.findExerciseByNormalized(normName)
        val catalogMatch = ExerciseCatalog.DEFAULT_CATALOG.firstOrNull {
            normalize(it.displayName) == normName || it.aliases.any { a -> normalize(a) == normName }
        }

        if (dbExercise == null && catalogMatch == null) {
            return IrisXResponse.Unrecognized(
                message = "No encontré '$exerciseName' en la biblioteca de ejercicios.",
                suggestions = listOf(
                    "Podés enseñarme un alias escribiendo: alias $exerciseName = [Nombre]",
                    "O consultar la biblioteca completa de ejercicios en tu Perfil."
                )
            )
        }

        val displayName = dbExercise?.displayName ?: catalogMatch!!.displayName
        val muscleGroup = dbExercise?.muscleGroup ?: catalogMatch!!.muscleGroup
        val secondary = dbExercise?.secondaryMuscleGroups ?: catalogMatch?.secondaryMuscleGroups ?: "Ninguno"
        val equipment = dbExercise?.equipment ?: catalogMatch?.equipment ?: "No especificado"

        val response = buildString {
            append("📌 $displayName\n")
            append("• Grupo principal: $muscleGroup\n")
            append("• Secundarios: $secondary\n")
            append("• Equipamiento: $equipment")
        }

        return IrisXResponse.Success(message = response, actionTaken = "QUERY_EXERCISE")
    }

    private suspend fun handleClassifySession(sessionTitle: String, exerciseNames: List<String>): IrisXResponse {
        val learnedAliases = getLearnedAliases()
        val classification = IrisXClassifier.classifyWorkout(sessionTitle, exerciseNames, learnedAliases)

        val response = buildString {
            append("Categoría: ${classification.primaryCategory.displayName}\n")
            append("Tipo de sesión: ${classification.sessionType}\n")
            if (classification.secondaryCategories.isNotEmpty()) {
                append("Categorías secundarias: ${classification.secondaryCategories.joinToString { it.displayName }}\n")
            }
            append("Explicación: ${classification.reasoning}")
        }

        return IrisXResponse.Success(
            message = response,
            actionTaken = "CLASSIFY_SESSION",
            payload = classification
        )
    }

    private fun handleGeneralQuery(text: String): IrisXResponse {
        val norm = normalize(text)
        return when {
            norm.contains("hola") || norm.contains("buen dia") || norm.contains("buenas") -> {
                IrisXResponse.Success(
                    "Hola. Soy IrisX, tu asistente local de entrenamiento en Syntra. ¿En qué te ayudo hoy?\n" +
                    "Podés consultar 'último entreno', 'info [ejercicio]', o enseñarme alias con 'alias [x] = [y]'."
                )
            }
            norm.contains("ayuda") || norm.contains("comandos") -> {
                IrisXResponse.Success(
                    "Comandos que reconozco offline:\n" +
                    "• 'último entreno' — Resumen de tu última sesión registrada.\n" +
                    "• 'info [ejercicio]' — Músculo y equipo de un ejercicio.\n" +
                    "• 'alias [nombre] = [ejercicio]' — Me enseña un nuevo alias.\n" +
                    "• 'musculo [ejercicio] = [grupo]' — Modifica el grupo muscular.\n" +
                    "• 'clasificar: [ejercicio 1, ejercicio 2]' — Clasifica un entrenamiento."
                )
            }
            else -> {
                IrisXResponse.Unrecognized(
                    message = "No entendí la consulta '$text'.",
                    suggestions = listOf(
                        "Escribí 'ayuda' para ver comandos disponibles.",
                        "Probá 'último entreno' o 'info [ejercicio]'."
                    )
                )
            }
        }
    }
}
