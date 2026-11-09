package com.syntra.app.domain.irisx

import com.syntra.app.data.AppDatabase
import com.syntra.app.data.ExerciseAliasEntity
import com.syntra.app.data.ExerciseEntity
import com.syntra.app.data.IrisxConversationEntity
import com.syntra.app.data.IrisxMemoryEntity
import com.syntra.app.data.IrisxMessageEntity
import com.syntra.app.data.ProfileEntity
import com.syntra.app.data.RoutineEntity
import com.syntra.app.data.RoutineExerciseEntity
import com.syntra.app.data.WorkoutSessionEntity
import com.syntra.app.data.WorkoutSetEntity
import com.syntra.app.domain.ExerciseCatalog
import com.syntra.app.domain.RoutineManager
import com.syntra.app.domain.RoutineWithExercises
import com.syntra.app.domain.SportsMetricsCalculator
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
    suspend fun getMuscleOverrides(): Map<String, String> = emptyMap()
    suspend fun saveLearnedAlias(alias: String, canonical: String)
    suspend fun deleteLearnedAlias(alias: String)
    suspend fun saveMemory(memoryType: String, sourceKey: String, targetValue: String)

    // Sprint 04 extensions with safe default implementations for backward compatibility
    suspend fun getAllSessions(): List<WorkoutSessionEntity> =
        listOfNotNull(getLatestSession())
    suspend fun getAllSets(): List<WorkoutSetEntity> =
        getLatestSession()?.let { getSetsForSession(it.id) } ?: emptyList()
    suspend fun getAllExercises(): List<ExerciseEntity> =
        getExerciseNameMap().map { (id, name) ->
            ExerciseEntity(
                id = id,
                displayName = name,
                normalizedName = IrisXAnalytics.normalize(name),
                muscleGroup = IrisXClassifier.detectMuscleGroup(name, getLearnedAliases(), getMuscleOverrides())
            )
        }
    suspend fun getProfile(): ProfileEntity? = null
    suspend fun getConfirmedPreferences(): Map<String, String> = emptyMap()
    suspend fun getRoutinesWithExercises(): List<RoutineWithExercises> = emptyList()
    suspend fun getOrCreateActiveConversation(): IrisxConversationEntity =
        IrisxConversationEntity(id = 1L, title = "Conversación activa")
    suspend fun updateConversationState(conversation: IrisxConversationEntity) {}
    suspend fun appendMessage(message: IrisxMessageEntity): Long = 0L
    suspend fun getMessages(conversationId: Long): List<IrisxMessageEntity> = emptyList()
    suspend fun deleteConversation(conversationId: Long) {}
    suspend fun clearAllConversations() {}
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

    override suspend fun getMuscleOverrides(): Map<String, String> {
        val memories = db.irisxMemoryDao().findByType("CLASSIFICATION_OVERRIDE")
        val exerciseGroups = db.exerciseDao().getAll().associate { it.normalizedName to it.muscleGroup }
        return exerciseGroups + memories.associate { it.sourceKey to it.targetValue }
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
        db.exerciseAliasDao().deleteByAlias(normAlias)
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

    override suspend fun getAllSessions(): List<WorkoutSessionEntity> {
        return db.workoutSessionDao().getAll()
    }

    override suspend fun getAllSets(): List<WorkoutSetEntity> {
        return db.workoutSetDao().getAll()
    }

    override suspend fun getAllExercises(): List<ExerciseEntity> {
        return db.exerciseDao().getAll()
    }

    override suspend fun getProfile(): ProfileEntity? {
        return db.profileDao().get()
    }

    override suspend fun getConfirmedPreferences(): Map<String, String> {
        return db.irisxMemoryDao().findByType("PREFERENCE")
            .associate { it.sourceKey to it.targetValue }
    }

    override suspend fun getRoutinesWithExercises(): List<RoutineWithExercises> {
        val routines = db.routineDao().getAllRoutines()
        val allItems = db.routineDao().getAllRoutineExercises().groupBy { it.routineId }
        return routines.map { r ->
            RoutineManager.fromEntities(r, allItems[r.id].orEmpty())
        }
    }

    override suspend fun getOrCreateActiveConversation(): IrisxConversationEntity {
        val existing = db.irisxConversationDao().getAllConversations().firstOrNull()
        if (existing != null) return existing
        val now = System.currentTimeMillis()
        val created = IrisxConversationEntity(
            title = "Conversación principal",
            activePeriodScope = PeriodScope.THIS_MONTH.key,
            createdAtMillis = now,
            updatedAtMillis = now
        )
        val id = db.irisxConversationDao().insertConversation(created)
        return created.copy(id = id)
    }

    override suspend fun updateConversationState(conversation: IrisxConversationEntity) {
        db.irisxConversationDao().updateConversation(conversation)
    }

    override suspend fun appendMessage(message: IrisxMessageEntity): Long {
        return db.irisxConversationDao().insertMessage(message)
    }

    override suspend fun getMessages(conversationId: Long): List<IrisxMessageEntity> {
        return db.irisxConversationDao().getMessagesForConversation(conversationId)
    }

    override suspend fun deleteConversation(conversationId: Long) {
        db.irisxConversationDao().deleteMessagesForConversation(conversationId)
        db.irisxConversationDao().deleteConversationById(conversationId)
    }

    override suspend fun clearAllConversations() {
        db.irisxConversationDao().deleteAllMessages()
        db.irisxConversationDao().deleteAllConversations()
    }
}

class IrisXCore(
    private val repository: IrisXRepository
) {
    private var contextState = ConversationContextState()

    fun getCurrentContextState(): ConversationContextState = contextState

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

        // 1. Query last session of a specific category/muscle vs general last workout
        val lastSessionCategoryRegex = Regex(
            "(?:ultima\\s+sesion|ultimo\\s+entreno|ultimo\\s+entrenamiento)\\s+de\\s+(.+)$"
        )
        val lastCatMatch = lastSessionCategoryRegex.find(norm)
        if (lastCatMatch != null) {
            val catTarget = lastCatMatch.groupValues[1].trim()
            if (catTarget.isNotBlank()) {
                return IrisXIntent.QueryLastSessionByCategory(catTarget)
            }
        }

        if (norm.contains("ultimo entreno") || norm.contains("ultima sesion") ||
            norm.contains("ultimo entrenamiento") || norm == "resumen" || norm == "ultimo"
        ) {
            return IrisXIntent.QueryLastWorkout
        }

        // 1b. Query last load in an exercise: e.g. "¿cuánto levanté la última vez en sentadilla?", "última carga en press de banca"
        val isLastLoadPattern = (norm.contains("cuanto levante") || norm.contains("cuanto hice") ||
            norm.contains("ultima carga") || norm.contains("ultimo peso") ||
            (norm.contains("ultima vez") && (norm.contains("en ") || norm.contains("con "))))
        if (isLastLoadPattern) {
            val exAfterPrep = Regex("(?:ultima\\s+vez\\s+(?:en|con)|levante\\s+(?:en|con)|hice\\s+(?:en|con)|carga\\s+(?:en|de)|peso\\s+(?:en|de)|en)\\s+(.+)$")
                .find(norm)?.groupValues?.get(1)
                ?.replace("la ultima vez", "")
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "este" && it != "este ejercicio" }
            return IrisXIntent.QueryLastExerciseLoad(exerciseName = exAfterPrep)
        }

        // 1c. Query exercise alternatives: e.g. "¿qué ejercicio puedo hacer en lugar de este?", "alternativa a press de banca", "reemplazo de sentadilla"
        val isAlternativePattern = norm.contains("en lugar de") || norm.contains("en vez de") ||
            norm.contains("alternativa") || norm.contains("reemplazo") || norm.contains("sustituto") ||
            (norm.contains("que ejercicio puedo hacer") && (norm.contains("para ") || norm.contains("en lugar")))
        if (isAlternativePattern) {
            val targetAfter = Regex("(?:en\\s+lugar\\s+de|en\\s+vez\\s+de|alternativas?\\s+(?:a|de|para)|reemplazos?\\s+(?:a|de|para)|sustitutos?\\s+(?:a|de|para)|hacer\\s+para)\\s+(.+)$")
                .find(norm)?.groupValues?.get(1)?.trim()
                ?.takeIf { it.isNotBlank() && it != "este" && it != "este ejercicio" && it != "eso" }
            return IrisXIntent.QueryExerciseAlternatives(exerciseName = targetAfter)
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

        // 6. Confirm permanent preference: "confirmar preferencia X = Y"
        val confirmPrefRegex = Regex("^confirmar\\s+preferencia\\s+(.+?)\\s*(?:=|es|->)\\s*(.+)$", RegexOption.IGNORE_CASE)
        val confirmPrefMatch = confirmPrefRegex.find(trimmed)
        if (confirmPrefMatch != null) {
            val (key, value) = confirmPrefMatch.destructured
            return IrisXIntent.SavePreference(key.trim(), value.trim(), confirmed = true)
        }

        // 7. Propose preference (requires confirmation): "preferencia X = Y"
        val prefRegex = Regex("^(?:guardar\\s+)?preferencia\\s+(.+?)\\s*(?:=|es|->)\\s*(.+)$", RegexOption.IGNORE_CASE)
        val prefMatch = prefRegex.find(trimmed)
        if (prefMatch != null) {
            val (key, value) = prefMatch.destructured
            return IrisXIntent.SavePreference(key.trim(), value.trim(), confirmed = false)
        }

        // 8. Query preferences
        if (norm == "preferencias" || norm == "mis preferencias" || norm.contains("ver preferencias")) {
            return IrisXIntent.QueryPreferences
        }

        // 9. Clear conversation history
        if (norm == "borrar conversacion" || norm == "limpiar chat" || norm == "eliminar conversacion" ||
            norm == "borrar historial de chat"
        ) {
            return IrisXIntent.ClearConversationHistory
        }

        // 10. Query routines
        if (norm == "rutinas" || norm == "mis rutinas" || norm.contains("ver rutinas")) {
            return IrisXIntent.QueryRoutines
        }

        // 11. Query exercise info: "ejercicio sentadilla" or "info sentadilla" or "buscar sentadilla"
        val exerciseQueryRegex = Regex("^(?:ejercicio|info|buscar)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val exerciseMatch = exerciseQueryRegex.find(trimmed)
        if (exerciseMatch != null) {
            val exName = exerciseMatch.groupValues[1].trim()
            return IrisXIntent.QueryExercise(exName)
        }

        // 12. Classify session text: "clasificar [ejercicios separados por comas o saltos]"
        val classifyRegex = Regex("^clasificar(?:\\s+sesion)?\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)
        val classifyMatch = classifyRegex.find(trimmed)
        if (classifyMatch != null) {
            val body = classifyMatch.groupValues[1]
            val lines = body.split(Regex("[,\\n]")).map { it.trim() }.filter { it.isNotBlank() }
            return IrisXIntent.ClassifySession(sessionTitle = "Sesión IrisX", exerciseNames = lines)
        }

        // 13. Coach recommendations: "recomendaciones", "coach", "que me recomendas...", "consejo..."
        if (norm.contains("recomendacion") || norm.contains("recomendas") || norm.contains("recomiendas") ||
            norm == "coach" || norm.startsWith("coach ") || norm.startsWith("consejo")
        ) {
            val muscleRef = IrisXContextResolver.detectMuscleReference(norm)
            val afterPreposition = Regex("(?:en|para|sobre)\\s+(.+)$").find(norm)?.groupValues?.get(1)?.trim()
            return IrisXIntent.QueryRecommendations(targetSubject = muscleRef ?: afterPreposition)
        }

        // 14. Progression / Analytics queries: e.g. "¿Cómo progresé en espalda?", "progreso sentadilla", "récord press de banca", "estancamiento"
        val isProgressionKeyword = norm.contains("progrese") || norm.contains("progreso") ||
            norm.contains("progresion") || norm.contains("evolucion") ||
            norm.contains("record") || norm.contains("pr ") || norm.startsWith("pr ") ||
            norm.contains("estancado") || norm.contains("estancamiento") ||
            norm.contains("estadistica") || norm.contains("volumen") || norm.contains("frecuencia")

        val detectedPeriod = IrisXContextResolver.detectPeriodScope(norm)
        val detectedMuscle = IrisXContextResolver.detectMuscleReference(norm)

        if (isProgressionKeyword) {
            // Extract target subject after "en ", "de ", "progreso ", "record "
            val subjectRegex = Regex("(?:progrese\\s+en|progreso\\s+(?:en|de)|progresion\\s+(?:en|de)|evolucion\\s+(?:en|de)|record\\s+(?:en|de)|estancamiento\\s+en)\\s+(.+)$")
            val extractedSubject = subjectRegex.find(norm)?.groupValues?.get(1)
                ?.replace(Regex("\\b(?:el mes pasado|este mes|la semana pasada|esta semana)\\b"), "")
                ?.trim()
            val target = detectedMuscle ?: extractedSubject?.takeIf { it.isNotBlank() }
            return IrisXIntent.QueryProgression(targetSubject = target, periodScope = detectedPeriod)
        }

        // 15. Contextual Follow-up queries: e.g. "¿Y el mes pasado?", "¿Y la semana pasada?", "¿Y en pecho?", "¿Y en sentadilla?"
        val isFollowUpPattern = norm.startsWith("y ") || norm.startsWith("¿y ") ||
            (detectedPeriod != null && norm.split(" ").size <= 5)
        if (isFollowUpPattern) {
            val followSubject = detectedMuscle ?: norm.removePrefix("y ").removePrefix("en ").removePrefix("de ")
                .takeIf { detectedPeriod == null && it.isNotBlank() }
            return IrisXIntent.FollowUpQuery(
                periodScope = detectedPeriod,
                targetSubject = followSubject,
                rawText = trimmed
            )
        }

        return IrisXIntent.GeneralQuery(trimmed)
    }

    /**
     * Full conversational entry point: parses input, executes intent with current context,
     * and persists user/assistant messages and updated context in SQLite.
     */
    suspend fun ask(
        input: String,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): IrisXResponse {
        val activeConv = repository.getOrCreateActiveConversation()
        if (contextState.conversationId == 0L) {
            contextState = activeConv.toState()
        }

        val intent = parseInput(input)
        repository.appendMessage(
            IrisxMessageEntity(
                conversationId = activeConv.id,
                senderRole = "USER",
                content = input.trim(),
                intentType = intent::class.simpleName,
                referencedExercise = contextState.activeExerciseName,
                referencedMuscleGroup = contextState.activeMuscleGroup,
                createdAtMillis = referenceTimeMillis
            )
        )

        val response = execute(intent, referenceTimeMillis)
        val replyText = when (response) {
            is IrisXResponse.Success -> response.message
            is IrisXResponse.RequiresConfirmation -> "${response.question}\nConfirmar con: ${response.confirmCommand}"
            is IrisXResponse.Unrecognized -> response.message
        }

        repository.appendMessage(
            IrisxMessageEntity(
                conversationId = activeConv.id,
                senderRole = "IRISX",
                content = replyText,
                intentType = intent::class.simpleName,
                referencedExercise = contextState.activeExerciseName,
                referencedMuscleGroup = contextState.activeMuscleGroup,
                createdAtMillis = referenceTimeMillis + 1
            )
        )

        repository.updateConversationState(
            activeConv.copy(
                activeMuscleGroup = contextState.activeMuscleGroup,
                activeExerciseName = contextState.activeExerciseName,
                activeSessionId = contextState.activeSessionId,
                activePeriodScope = contextState.activePeriodScope.key,
                updatedAtMillis = referenceTimeMillis + 1
            )
        )

        return response
    }

    /**
     * Executes an IrisXIntent deterministically using repository and domain rules.
     */
    suspend fun execute(
        intent: IrisXIntent,
        referenceTimeMillis: Long = System.currentTimeMillis()
    ): IrisXResponse {
        return when (intent) {
            is IrisXIntent.QueryLastWorkout -> handleQueryLastWorkout()
            is IrisXIntent.LearnAlias -> handleLearnAlias(intent.alias, intent.canonical, intent.force)
            is IrisXIntent.DeleteAlias -> handleDeleteAlias(intent.alias)
            is IrisXIntent.UpdateExerciseMuscle -> handleUpdateExerciseMuscle(intent.exerciseName, intent.muscleGroup)
            is IrisXIntent.QueryExercise -> handleQueryExercise(intent.exerciseName)
            is IrisXIntent.ClassifySession -> handleClassifySession(intent.sessionTitle, intent.exerciseNames)
            is IrisXIntent.QueryProgression -> handleQueryProgression(intent.targetSubject, intent.periodScope, referenceTimeMillis)
            is IrisXIntent.FollowUpQuery -> handleFollowUpQuery(intent, referenceTimeMillis)
            is IrisXIntent.QueryRecommendations -> handleQueryRecommendations(intent.targetSubject, referenceTimeMillis)
            is IrisXIntent.SavePreference -> handleSavePreference(intent.key, intent.value, intent.confirmed)
            is IrisXIntent.QueryPreferences -> handleQueryPreferences()
            is IrisXIntent.QueryRoutines -> handleQueryRoutines()
            is IrisXIntent.ClearConversationHistory -> handleClearConversations()
            is IrisXIntent.QueryLastExerciseLoad -> handleQueryLastExerciseLoad(intent.exerciseName)
            is IrisXIntent.QueryExerciseAlternatives -> handleQueryExerciseAlternatives(intent.exerciseName)
            is IrisXIntent.QueryLastSessionByCategory -> handleQueryLastSessionByCategory(intent.categoryOrMuscle)
            is IrisXIntent.GeneralQuery -> handleGeneralQuery(intent.text, referenceTimeMillis)
        }
    }

    suspend fun getLearnedAliases(): Map<String, String> {
        return repository.getLearnedAliases()
    }

    private suspend fun handleQueryLastExerciseLoad(requestedExercise: String?): IrisXResponse {
        val effectiveExercise = requestedExercise?.takeIf { it.isNotBlank() }
            ?: contextState.activeExerciseName
            ?: return IrisXResponse.Unrecognized(
                message = "Indicame qué ejercicio querés consultar (por ejemplo: '¿cuánto levanté la última vez en sentadilla?')."
            )

        val sessions = repository.getAllSessions()
        val allSets = repository.getAllSets()
        val exercises = repository.getAllExercises()
        val aliases = repository.getLearnedAliases()

        val report = IrisXAnalytics.analyzeExerciseProgression(
            exerciseName = effectiveExercise,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = aliases
        )

        val lastPerf = report.history.lastOrNull()
            ?: return IrisXResponse.Success(
                message = "Todavía no tenés series registradas en '${report.exerciseName}'.",
                actionTaken = "QUERY_LAST_EXERCISE_LOAD_EMPTY"
            )

        val sessionSets = allSets
            .filter { it.sessionId == lastPerf.sessionId }
            .filter { set ->
                val exName = exercises.firstOrNull { it.id == set.exerciseId }?.displayName.orEmpty()
                normalize(exName) == normalize(report.exerciseName) ||
                    normalize(aliases[normalize(exName)] ?: exName) == normalize(report.exerciseName)
            }
            .sortedWith(compareBy<WorkoutSetEntity> { it.executionOrder }.thenBy { it.id })

        val dateStr = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(lastPerf.timestampMillis))
        val topKg = SportsMetricsCalculator.formatKg(lastPerf.topLoadGrams / 1000.0)
        val setsSummary = if (sessionSets.isNotEmpty()) {
            sessionSets.joinToString(", ") { s ->
                val kg = SportsMetricsCalculator.formatKg(s.loadGrams / 1000.0)
                val mod = when {
                    s.isAssisted -> " asist."
                    s.isDumbbell -> " c/u"
                    else -> ""
                }
                "$kg$mod × ${s.repetitions}"
            }
        } else {
            "$topKg × ${lastPerf.topLoadReps} reps (${lastPerf.setsCount} series)"
        }

        contextState = contextState.copy(
            activeExerciseName = report.exerciseName,
            activeSessionId = lastPerf.sessionId,
            lastQueryDomain = "LAST_EXERCISE_LOAD"
        )

        val msg = buildString {
            append("🏋️ Último registro en ${report.exerciseName} ($dateStr):\n")
            append("• Mejor serie: $topKg × ${lastPerf.topLoadReps} reps\n")
            append("• Series ejecutadas (${lastPerf.setsCount}): $setsSummary")
        }

        return IrisXResponse.Success(
            message = msg,
            actionTaken = "QUERY_LAST_EXERCISE_LOAD",
            payload = lastPerf
        )
    }

    private suspend fun handleQueryExerciseAlternatives(requestedExercise: String?): IrisXResponse {
        val effectiveTarget = requestedExercise?.takeIf { it.isNotBlank() }
            ?: contextState.activeExerciseName
            ?: contextState.activeMuscleGroup
            ?: return IrisXResponse.Unrecognized(
                message = "Indicame para qué ejercicio o grupo muscular buscás una alternativa (ej. '¿qué ejercicio puedo hacer en lugar de press de banca?')."
            )

        val normTarget = normalize(effectiveTarget)
        val aliases = repository.getLearnedAliases()
        val overrides = repository.getMuscleOverrides()
        val canonicalTarget = aliases[normTarget] ?: effectiveTarget
        val normCanonical = normalize(canonicalTarget)

        val dbExercises = repository.getAllExercises()
        val matchedDb = dbExercises.firstOrNull { normalize(it.displayName) == normCanonical }
        val matchedCatalog = ExerciseCatalog.DEFAULT_CATALOG.firstOrNull {
            normalize(it.displayName) == normCanonical || it.aliases.any { a -> normalize(a) == normTarget }
        }

        val targetDisplayName = matchedDb?.displayName ?: matchedCatalog?.displayName ?: canonicalTarget
        val targetMuscle = matchedDb?.muscleGroup?.takeIf { it.isNotBlank() && it != "General" }
            ?: matchedCatalog?.muscleGroup
            ?: IrisXContextResolver.detectMuscleReference(normCanonical)
            ?: IrisXClassifier.detectMuscleGroup(targetDisplayName, aliases, overrides)

        val normMuscle = normalize(targetMuscle)
        val candidatePool = (dbExercises.map { it.displayName to it.muscleGroup } +
            ExerciseCatalog.DEFAULT_CATALOG.map { it.displayName to it.muscleGroup })
            .distinctBy { normalize(it.first) }

        val alternatives = candidatePool
            .filter { (name, muscle) ->
                normalize(name) != normCanonical &&
                    normalize(name) != normTarget &&
                    (normalize(muscle) == normMuscle ||
                        IrisXClassifier.mapMuscleToCategory(muscle) == IrisXClassifier.mapMuscleToCategory(targetMuscle))
            }
            .map { it.first }
            .take(5)

        contextState = contextState.copy(
            activeExerciseName = targetDisplayName,
            activeMuscleGroup = targetMuscle,
            lastQueryDomain = "EXERCISE_ALTERNATIVES"
        )

        if (alternatives.isEmpty()) {
            return IrisXResponse.Success(
                message = "No encontré alternativas catalogadas para '$targetDisplayName' ($targetMuscle).",
                actionTaken = "QUERY_ALTERNATIVES_EMPTY"
            )
        }

        val msg = buildString {
            append("🔄 Alternativas biomecánicas para $targetDisplayName (grupo: $targetMuscle):\n")
            alternatives.forEach { alt ->
                append("• $alt\n")
            }
        }.trim()

        return IrisXResponse.Success(
            message = msg,
            actionTaken = "QUERY_EXERCISE_ALTERNATIVES",
            payload = alternatives
        )
    }

    private suspend fun handleQueryLastSessionByCategory(categoryOrMuscle: String): IrisXResponse {
        val sessions = repository.getAllSessions()
        if (sessions.isEmpty()) {
            return IrisXResponse.Success(
                message = "No tenés entrenamientos registrados todavía.",
                actionTaken = "QUERY_LAST_CATEGORY_EMPTY"
            )
        }

        val allSets = repository.getAllSets().groupBy { it.sessionId }
        val exercisesById = repository.getAllExercises().associateBy { it.id }
        val aliases = repository.getLearnedAliases()
        val overrides = repository.getMuscleOverrides()
        val normRequested = normalize(categoryOrMuscle)
        val detectedMuscle = IrisXContextResolver.detectMuscleReference(normRequested) ?: categoryOrMuscle

        val sortedSessions = sessions.sortedWith(
            compareByDescending<WorkoutSessionEntity> { it.startedAtMillis }.thenByDescending { it.id }
        )

        val matchedSession = sortedSessions.firstOrNull { session ->
            val sets = allSets[session.id].orEmpty()
            val exNames = sets.mapNotNull { exercisesById[it.exerciseId]?.displayName }.distinct()
            val classification = IrisXClassifier.classifyWorkout(session.title, exNames, aliases, overrides)
            val normPrimary = normalize(classification.primaryCategory.displayName)
            val normTitle = normalize(session.title)
            val normDetected = normalize(detectedMuscle)
            normPrimary == normDetected ||
                normPrimary.contains(normDetected) ||
                normDetected.contains(normPrimary) ||
                normTitle.contains(normRequested) ||
                exNames.any { ex ->
                    normalize(IrisXClassifier.detectMuscleGroup(ex, aliases, overrides)).contains(normDetected)
                }
        }

        if (matchedSession == null) {
            return IrisXResponse.Success(
                message = "No encontré ninguna sesión previa clasificada en '$categoryOrMuscle'.",
                actionTaken = "QUERY_LAST_CATEGORY_NOT_FOUND"
            )
        }

        val sessionSets = allSets[matchedSession.id].orEmpty()
            .sortedWith(compareBy<WorkoutSetEntity> { it.executionOrder }.thenBy { it.id })
        val dateStr = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(matchedSession.startedAtMillis))
        val byExercise = sessionSets.groupBy { exercisesById[it.exerciseId]?.displayName ?: "Ejercicio #${it.exerciseId}" }

        contextState = contextState.copy(
            activeSessionId = matchedSession.id,
            activeMuscleGroup = detectedMuscle,
            lastQueryDomain = "LAST_CATEGORY_WORKOUT"
        )

        val msg = buildString {
            append("📋 Última sesión de ${detectedMuscle.replaceFirstChar { it.titlecase(Locale.ROOT) }}: ${matchedSession.title} ($dateStr)\n")
            append("Series totales: ${sessionSets.size} | Ejercicios: ${byExercise.size}\n")
            byExercise.forEach { (exName, sets) ->
                val formattedSets = sets.joinToString(", ") { s ->
                    "${SportsMetricsCalculator.formatKg(s.loadGrams / 1000.0)} × ${s.repetitions}"
                }
                append("• $exName: $formattedSets\n")
            }
        }.trim()

        return IrisXResponse.Success(
            message = msg,
            actionTaken = "QUERY_LAST_SESSION_BY_CATEGORY",
            payload = matchedSession
        )
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
        val muscleOverrides = repository.getMuscleOverrides()
        val classification = IrisXClassifier.classifyWorkout(latest.title, exerciseNames, learnedAliases, muscleOverrides)
        val metrics = SportsMetricsCalculator.calculateSessionVolume(
            sets = sets,
            exerciseNamesById = exercises
        )

        contextState = contextState.copy(
            activeSessionId = latest.id,
            activeMuscleGroup = classification.primaryCategory.displayName.takeIf { it != "Otras / Sin clasificar" },
            lastQueryDomain = "LAST_WORKOUT"
        )

        val summaryText = buildString {
            append("Última sesión: ${latest.title} ($dateStr)\n")
            append("Categoría: ${classification.primaryCategory.displayName} (${classification.sessionType})\n")
            append("Series totales: ${sets.size} | Carga externa: ${String.format(Locale.ROOT, "%.1f", metrics.totalExternalKg)} kg\n")
            if (metrics.dumbbellSetsCount > 0 || metrics.assistedSetsCount > 0 || metrics.unilateralSetsCount > 0 || metrics.weightedBodyweightSetsCount > 0) {
                append("(${metrics.regularSetsCount} barra/máq., ${metrics.dumbbellSetsCount} mancuernas [c/u], ${metrics.unilateralSetsCount} unilat., ${metrics.assistedSetsCount} asistidas)\n")
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

    private suspend fun handleQueryProgression(
        targetSubject: String?,
        periodScope: PeriodScope?,
        referenceTimeMillis: Long
    ): IrisXResponse {
        val sessions = repository.getAllSessions()
        val allSets = repository.getAllSets()
        val exercises = repository.getAllExercises()
        val aliases = repository.getLearnedAliases()
        val overrides = repository.getMuscleOverrides()

        if (sessions.isEmpty() || allSets.isEmpty()) {
            return IrisXResponse.Success(
                message = "Datos insuficientes: todavía no tenés sesiones registradas para analizar progresión.",
                actionTaken = "QUERY_PROGRESSION_EMPTY"
            )
        }

        val resolvedPeriod = periodScope ?: PeriodScope.THIS_MONTH
        val effectiveSubject = targetSubject
            ?: contextState.activeExerciseName
            ?: contextState.activeMuscleGroup

        // If no specific exercise or muscle was requested, return global frequency and period summary
        if (effectiveSubject.isNullOrBlank()) {
            val freq = IrisXAnalytics.calculateTrainingFrequency(sessions, 28, referenceTimeMillis)
            val dist = IrisXAnalytics.calculateMuscleDistribution(sessions, allSets, exercises, aliases, overrides)
            contextState = contextState.copy(
                activePeriodScope = resolvedPeriod,
                lastQueryDomain = "PROGRESSION"
            )
            val msg = buildString {
                append("📈 Resumen general de rendimiento\n")
                append("• ${freq.summaryText}\n")
                if (dist.setsByMuscle.isNotEmpty()) {
                    val topMuscles = dist.setsByMuscle.entries.sortedByDescending { it.value }
                        .joinToString(", ") { "${it.key}: ${it.value} series (${dist.percentagesBySets[it.key]}%)" }
                    append("• Distribución por grupo muscular: $topMuscles")
                }
            }
            return IrisXResponse.Success(message = msg, actionTaken = "QUERY_GLOBAL_PROGRESSION", payload = freq)
        }

        val normSubj = normalize(effectiveSubject)
        val detectedMuscle = IrisXContextResolver.detectMuscleReference(normSubj)
        val detectedExercise = IrisXContextResolver.detectExerciseReference(
            normInput = normSubj,
            knownExerciseNames = exercises.map { it.displayName },
            learnedAliases = aliases
        )

        // Case 1: Specific Exercise matched
        if (detectedExercise != null || (detectedMuscle == null && exercises.any { normalize(it.displayName).contains(normSubj) })) {
            val exName = detectedExercise ?: effectiveSubject
            val window = IrisXContextResolver.resolvePeriodWindow(resolvedPeriod, referenceTimeMillis)
            val filteredSessions = if (periodScope != null) {
                sessions.filter { it.startedAtMillis in window.currentStartMillis..window.currentEndMillis }
            } else sessions

            val report = IrisXAnalytics.analyzeExerciseProgression(
                exerciseName = exName,
                sessions = filteredSessions,
                allSets = allSets,
                exercises = exercises,
                learnedAliases = aliases
            )

            contextState = contextState.copy(
                activeExerciseName = report.exerciseName,
                activeMuscleGroup = null,
                activePeriodScope = resolvedPeriod,
                lastQueryDomain = "PROGRESSION"
            )

            return IrisXResponse.Success(
                message = report.summaryText,
                actionTaken = "QUERY_EXERCISE_PROGRESSION",
                payload = report
            )
        }

        // Case 2: Muscle group matched (e.g. "¿Cómo progresé en espalda?")
        val muscleTarget = detectedMuscle ?: effectiveSubject.replaceFirstChar { it.titlecase(Locale.ROOT) }
        val window = IrisXContextResolver.resolvePeriodWindow(resolvedPeriod, referenceTimeMillis)
        val comparison = IrisXAnalytics.comparePeriods(
            periodALabel = window.previousLabel,
            periodAStartMillis = window.previousStartMillis,
            periodAEndMillis = window.previousEndMillis,
            periodBLabel = window.currentLabel,
            periodBStartMillis = window.currentStartMillis,
            periodBEndMillis = window.currentEndMillis,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            muscleFilter = muscleTarget,
            learnedAliases = aliases,
            muscleOverrides = overrides
        )

        // Find exercises belonging to this muscle group that have history
        val muscleExercises = exercises.filter { ex ->
            val m = if (ex.muscleGroup.isNotBlank() && ex.muscleGroup != "General") ex.muscleGroup
            else IrisXClassifier.detectMuscleGroup(ex.displayName, aliases, overrides)
            normalize(m).contains(normalize(muscleTarget)) ||
                normalize(IrisXClassifier.mapMuscleToCategory(m).displayName).contains(normalize(muscleTarget))
        }

        val exerciseSummaries = muscleExercises.mapNotNull { ex ->
            val r = IrisXAnalytics.analyzeExerciseProgression(ex.displayName, sessions, allSets, exercises, aliases)
            if (r.history.isNotEmpty()) {
                val last = r.history.last()
                val kg = SportsMetricsCalculator.formatKg(last.topLoadGrams / 1000.0)
                "• ${r.exerciseName}: ${r.status.displayName} (último tope: $kg × ${last.topLoadReps} reps)"
            } else null
        }

        contextState = contextState.copy(
            activeMuscleGroup = muscleTarget,
            activeExerciseName = null,
            activePeriodScope = resolvedPeriod,
            lastQueryDomain = "PROGRESSION"
        )

        val responseMsg = buildString {
            append("📊 Análisis de $muscleTarget (${window.currentLabel})\n")
            append(comparison.summaryText)
            if (exerciseSummaries.isNotEmpty()) {
                append("\nEjercicios registrados de $muscleTarget:\n")
                append(exerciseSummaries.joinToString("\n"))
            } else {
                append("\nNo se encontraron ejercicios con series para $muscleTarget en este período.")
            }
        }

        return IrisXResponse.Success(
            message = responseMsg,
            actionTaken = "QUERY_MUSCLE_PROGRESSION",
            payload = comparison
        )
    }

    private suspend fun handleFollowUpQuery(
        intent: IrisXIntent.FollowUpQuery,
        referenceTimeMillis: Long
    ): IrisXResponse {
        val currentMuscle = contextState.activeMuscleGroup
        val currentExercise = contextState.activeExerciseName

        if (currentMuscle == null && currentExercise == null && intent.targetSubject == null) {
            return IrisXResponse.Unrecognized(
                message = "No tengo un ejercicio o grupo muscular activo en nuestra conversación para comparar '${intent.rawText}'.",
                suggestions = listOf(
                    "Preguntame primero: '¿Cómo progresé en espalda?' o 'progreso Sentadilla'.",
                    "Luego podés preguntar: '¿Y el mes pasado?'"
                )
            )
        }

        val targetSubject = intent.targetSubject ?: currentExercise ?: currentMuscle
        val targetPeriod = intent.periodScope ?: contextState.activePeriodScope

        return handleQueryProgression(
            targetSubject = targetSubject,
            periodScope = targetPeriod,
            referenceTimeMillis = referenceTimeMillis
        )
    }

    private suspend fun handleQueryRecommendations(
        targetSubject: String?,
        referenceTimeMillis: Long
    ): IrisXResponse {
        val profile = repository.getProfile()
        val sessions = repository.getAllSessions()
        val allSets = repository.getAllSets()
        val exercises = repository.getAllExercises()
        val aliases = repository.getLearnedAliases()
        val overrides = repository.getMuscleOverrides()
        val prefs = repository.getConfirmedPreferences()

        val effectiveTarget = targetSubject ?: contextState.activeExerciseName ?: contextState.activeMuscleGroup
        val recommendations = IrisXCoach.generateRecommendations(
            profile = profile,
            sessions = sessions,
            allSets = allSets,
            exercises = exercises,
            learnedAliases = aliases,
            muscleOverrides = overrides,
            confirmedPreferences = prefs,
            targetExerciseOrMuscle = effectiveTarget,
            referenceTimeMillis = referenceTimeMillis
        )

        contextState = contextState.copy(lastQueryDomain = "RECOMMENDATION")

        val text = buildString {
            append("🧭 IrisX Coach — Recomendaciones verificables\n\n")
            recommendations.forEachIndexed { idx, rec ->
                if (idx > 0) append("\n\n---\n\n")
                append(rec.formatForDisplay())
            }
        }

        return IrisXResponse.Success(
            message = text,
            actionTaken = "QUERY_RECOMMENDATIONS",
            payload = recommendations
        )
    }

    private suspend fun handleSavePreference(
        key: String,
        value: String,
        confirmed: Boolean
    ): IrisXResponse {
        val normKey = normalize(key)
        val cleanVal = value.trim()
        if (normKey.isBlank() || cleanVal.isBlank()) {
            return IrisXResponse.Unrecognized("La clave y el valor de la preferencia no pueden estar vacíos.")
        }

        if (!confirmed) {
            return IrisXResponse.RequiresConfirmation(
                question = "Por seguridad, no guardo afirmaciones como preferencias permanentes sin confirmación explícita. ¿Confirmás guardar la preferencia '$normKey' = '$cleanVal'?",
                confirmCommand = "confirmar preferencia $normKey = $cleanVal"
            )
        }

        repository.saveMemory("PREFERENCE", normKey, cleanVal)
        return IrisXResponse.Success(
            message = "Preferencia confirmada y guardada: '$normKey' = '$cleanVal'.",
            actionTaken = "SAVE_PREFERENCE"
        )
    }

    private suspend fun handleQueryPreferences(): IrisXResponse {
        val prefs = repository.getConfirmedPreferences()
        if (prefs.isEmpty()) {
            return IrisXResponse.Success(
                message = "No tenés preferencias permanentes confirmadas. Podés guardar una con 'preferencia objetivo = fuerza'.",
                actionTaken = "QUERY_PREFERENCES"
            )
        }
        val text = buildString {
            append("⚙ Preferencias confirmadas en IrisX Memory:\n")
            prefs.forEach { (k, v) ->
                append("• $k: $v\n")
            }
        }.trim()
        return IrisXResponse.Success(message = text, actionTaken = "QUERY_PREFERENCES", payload = prefs)
    }

    private suspend fun handleQueryRoutines(): IrisXResponse {
        val routines = repository.getRoutinesWithExercises()
        if (routines.isEmpty()) {
            return IrisXResponse.Success(
                message = "Todavía no tenés rutinas creadas. Podés crear una en la sección Rutinas.",
                actionTaken = "QUERY_ROUTINES"
            )
        }
        val text = buildString {
            append("📋 Rutinas disponibles (${routines.size}):\n")
            routines.forEach { rw ->
                append("• ${rw.routine.name} (${rw.exercises.size} ejercicios): ")
                append(rw.exercises.joinToString(", ") { "${it.exerciseName} (${it.targetSets}×${it.formattedRepRange()})" })
                append("\n")
            }
        }.trim()
        return IrisXResponse.Success(message = text, actionTaken = "QUERY_ROUTINES", payload = routines)
    }

    private suspend fun handleClearConversations(): IrisXResponse {
        repository.clearAllConversations()
        contextState = ConversationContextState()
        return IrisXResponse.Success(
            message = "Se eliminó el historial de conversaciones y se reinició el contexto activo. Tus entrenamientos y preferencias confirmadas permanecen intactos.",
            actionTaken = "CLEAR_CONVERSATIONS"
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

        contextState = contextState.copy(
            activeExerciseName = displayName,
            activeMuscleGroup = muscleGroup,
            lastQueryDomain = "EXERCISE_INFO"
        )

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
        val muscleOverrides = repository.getMuscleOverrides()
        val classification = IrisXClassifier.classifyWorkout(sessionTitle, exerciseNames, learnedAliases, muscleOverrides)

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

    private suspend fun handleGeneralQuery(text: String, referenceTimeMillis: Long): IrisXResponse {
        val norm = normalize(text)
        if (norm.isBlank()) {
            return IrisXResponse.Unrecognized(
                message = "Escribí una consulta o comando para IrisX.",
                suggestions = listOf("Probá 'último entreno', '¿cómo progresé en espalda?' o 'recomendaciones'.")
            )
        }

        // Check if the query directly mentions a known exercise or muscle group and asks a question
        val exercises = repository.getAllExercises()
        val aliases = repository.getLearnedAliases()
        val detectedMuscle = IrisXContextResolver.detectMuscleReference(norm)
        val detectedEx = IrisXContextResolver.detectExerciseReference(norm, exercises.map { it.displayName }, aliases)
        val detectedPeriod = IrisXContextResolver.detectPeriodScope(norm)

        if ((detectedMuscle != null || detectedEx != null) &&
            (norm.contains("como") || norm.contains("cuanto") || detectedPeriod != null)
        ) {
            return handleQueryProgression(
                targetSubject = detectedEx ?: detectedMuscle,
                periodScope = detectedPeriod,
                referenceTimeMillis = referenceTimeMillis
            )
        }

        return when {
            norm.contains("hola") || norm.contains("buen dia") || norm.contains("buenas") -> {
                IrisXResponse.Success(
                    "Hola. Soy IrisX, tu asistente local de entrenamiento en Syntra. ¿En qué te ayudo hoy?\n" +
                    "Podés consultar '¿cómo progresé en espalda?', 'recomendaciones', 'último entreno' o 'info [ejercicio]'."
                )
            }
            norm.contains("ayuda") || norm.contains("comandos") -> {
                IrisXResponse.Success(
                    "Comandos y consultas deterministas offline:\n" +
                    "• '¿cómo progresé en [músculo/ejercicio]?' — Análisis histórico y PRs.\n" +
                    "• '¿y el mes pasado?' — Pregunta de seguimiento con contexto activo.\n" +
                    "• 'recomendaciones' — Propuestas justificadas de IrisX Coach.\n" +
                    "• 'último entreno' — Resumen de tu última sesión registrada.\n" +
                    "• 'info [ejercicio]' — Músculo y equipo de un ejercicio.\n" +
                    "• 'alias [nombre] = [ejercicio]' — Me enseña un nuevo alias.\n" +
                    "• 'preferencia [clave] = [valor]' — Propone guardar preferencia.\n" +
                    "• 'borrar conversación' — Limpia el historial de chat."
                )
            }
            else -> {
                IrisXResponse.Unrecognized(
                    message = "No entendí la consulta '$text'.",
                    suggestions = listOf(
                        "Escribí 'ayuda' para ver comandos disponibles.",
                        "Probá '¿cómo progresé en espalda?', 'recomendaciones' o 'último entreno'."
                    )
                )
            }
        }
    }
}
