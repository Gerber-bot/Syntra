package com.syntra.app.data.backup

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object BackupSerializer {

    const val MIN_SUPPORTED_FORMAT_VERSION = 1
    const val CURRENT_FORMAT_VERSION = 2

    fun serialize(payload: SyntraBackupPayload): String {
        val root = JSONObject()
        root.put("formatVersion", payload.formatVersion)
        root.put("appVersion", payload.appVersion)
        root.put("exportedAtMillis", payload.exportedAtMillis)

        if (payload.profile != null) {
            val p = payload.profile
            val profileObj = JSONObject().apply {
                put("name", p.name)
                put("preferredName", p.preferredName)
                put("heightCm", p.heightCm)
                put("initialWeightGrams", p.initialWeightGrams)
                put("activityLevel", p.activityLevel)
                put("goal", p.goal)
                put("createdAtMillis", p.createdAtMillis)
                put("updatedAtMillis", p.updatedAtMillis)
            }
            root.put("profile", profileObj)
        } else {
            root.put("profile", JSONObject.NULL)
        }

        val weighInsArr = JSONArray()
        payload.weighIns.forEach { w ->
            weighInsArr.put(JSONObject().apply {
                put("id", w.id)
                put("measuredAtMillis", w.measuredAtMillis)
                put("weightGrams", w.weightGrams)
                put("note", w.note)
            })
        }
        root.put("weighIns", weighInsArr)

        val exercisesArr = JSONArray()
        payload.exercises.forEach { e ->
            exercisesArr.put(JSONObject().apply {
                put("id", e.id)
                put("displayName", e.displayName)
                put("normalizedName", e.normalizedName)
                put("muscleGroup", e.muscleGroup)
                put("secondaryMuscleGroups", e.secondaryMuscleGroups)
                put("equipment", e.equipment ?: JSONObject.NULL)
                put("exerciseType", e.exerciseType)
                put("status", e.status)
                put("createdAtMillis", e.createdAtMillis)
            })
        }
        root.put("exercises", exercisesArr)

        val aliasesArr = JSONArray()
        payload.exerciseAliases.forEach { a ->
            aliasesArr.put(JSONObject().apply {
                put("id", a.id)
                put("exerciseId", a.exerciseId)
                put("normalizedAlias", a.normalizedAlias)
                put("createdAtMillis", a.createdAtMillis)
            })
        }
        root.put("exerciseAliases", aliasesArr)

        val sessionsArr = JSONArray()
        payload.workoutSessions.forEach { s ->
            sessionsArr.put(JSONObject().apply {
                put("id", s.id)
                put("title", s.title)
                put("startedAtMillis", s.startedAtMillis)
                put("finishedAtMillis", s.finishedAtMillis ?: JSONObject.NULL)
                put("notes", s.notes)
            })
        }
        root.put("workoutSessions", sessionsArr)

        val setsArr = JSONArray()
        payload.workoutSets.forEach { set ->
            setsArr.put(JSONObject().apply {
                put("id", set.id)
                put("sessionId", set.sessionId)
                put("exerciseId", set.exerciseId)
                put("orderInExercise", set.orderInExercise)
                put("executionOrder", set.executionOrder)
                put("loadGrams", set.loadGrams)
                put("repetitions", set.repetitions)
                put("rir", set.rir ?: JSONObject.NULL)
                put("isAssisted", set.isAssisted)
                put("isDumbbell", set.isDumbbell)
                put("note", set.note)
                put("plannedTarget", set.plannedTarget ?: JSONObject.NULL)
            })
        }
        root.put("workoutSets", setsArr)

        val memoriesArr = JSONArray()
        payload.irisxMemories.forEach { m ->
            memoriesArr.put(JSONObject().apply {
                put("id", m.id)
                put("memoryType", m.memoryType)
                put("sourceKey", m.sourceKey)
                put("targetValue", m.targetValue)
                put("confidence", m.confidence)
                put("createdAtMillis", m.createdAtMillis)
                put("updatedAtMillis", m.updatedAtMillis)
            })
        }
        root.put("irisxMemories", memoriesArr)

        val conversationsArr = JSONArray()
        payload.irisxConversations.forEach { c ->
            conversationsArr.put(JSONObject().apply {
                put("id", c.id)
                put("title", c.title)
                put("activeMuscleGroup", c.activeMuscleGroup ?: JSONObject.NULL)
                put("activeExerciseName", c.activeExerciseName ?: JSONObject.NULL)
                put("activeSessionId", c.activeSessionId ?: JSONObject.NULL)
                put("activePeriodScope", c.activePeriodScope ?: JSONObject.NULL)
                put("createdAtMillis", c.createdAtMillis)
                put("updatedAtMillis", c.updatedAtMillis)
            })
        }
        root.put("irisxConversations", conversationsArr)

        val messagesArr = JSONArray()
        payload.irisxMessages.forEach { msg ->
            messagesArr.put(JSONObject().apply {
                put("id", msg.id)
                put("conversationId", msg.conversationId)
                put("senderRole", msg.senderRole)
                put("content", msg.content)
                put("intentType", msg.intentType ?: JSONObject.NULL)
                put("referencedExercise", msg.referencedExercise ?: JSONObject.NULL)
                put("referencedMuscleGroup", msg.referencedMuscleGroup ?: JSONObject.NULL)
                put("referencedSessionId", msg.referencedSessionId ?: JSONObject.NULL)
                put("createdAtMillis", msg.createdAtMillis)
            })
        }
        root.put("irisxMessages", messagesArr)

        val routinesArr = JSONArray()
        payload.routines.forEach { r ->
            routinesArr.put(JSONObject().apply {
                put("id", r.id)
                put("name", r.name)
                put("description", r.description)
                put("targetCategory", r.targetCategory)
                put("createdAtMillis", r.createdAtMillis)
                put("updatedAtMillis", r.updatedAtMillis)
            })
        }
        root.put("routines", routinesArr)

        val routineExArr = JSONArray()
        payload.routineExercises.forEach { re ->
            routineExArr.put(JSONObject().apply {
                put("id", re.id)
                put("routineId", re.routineId)
                put("exerciseId", re.exerciseId ?: JSONObject.NULL)
                put("exerciseName", re.exerciseName)
                put("orderIndex", re.orderIndex)
                put("targetSets", re.targetSets)
                put("minReps", re.minReps)
                put("maxReps", re.maxReps)
                put("suggestedLoadGrams", re.suggestedLoadGrams ?: JSONObject.NULL)
                put("isDumbbell", re.isDumbbell)
                put("isAssisted", re.isAssisted)
                put("notes", re.notes)
            })
        }
        root.put("routineExercises", routineExArr)

        return root.toString(2)
    }

    fun deserializeAndValidate(jsonString: String): BackupValidationResult {
        if (jsonString.isBlank()) {
            return BackupValidationResult.Error("El contenido del archivo está vacío.")
        }

        val root = try {
            JSONObject(jsonString)
        } catch (e: JSONException) {
            return BackupValidationResult.Error("Formato JSON inválido o corrupto: ${e.message}")
        }

        if (!root.has("formatVersion")) {
            return BackupValidationResult.Error("El respaldo no contiene el campo obligatorio 'formatVersion'.")
        }

        val formatVersion = root.optInt("formatVersion", -1)
        if (formatVersion !in MIN_SUPPORTED_FORMAT_VERSION..CURRENT_FORMAT_VERSION) {
            return BackupValidationResult.Error(
                "Versión de esquema '$formatVersion' no soportada (soportadas: $MIN_SUPPORTED_FORMAT_VERSION..$CURRENT_FORMAT_VERSION)."
            )
        }

        val appVersion = root.optString("appVersion", "desconocida")
        val exportedAtMillis = root.optLong("exportedAtMillis", 0L)
        if (exportedAtMillis <= 0L) {
            return BackupValidationResult.Error("Fecha de exportación inválida o ausente.")
        }

        // Profile validation (optional but if present must be valid)
        val profileDto = if (root.has("profile") && !root.isNull("profile")) {
            val pObj = root.getJSONObject("profile")
            val name = pObj.optString("name", "").trim()
            val preferredName = pObj.optString("preferredName", "").trim()
            val heightCm = pObj.optInt("heightCm", 0)
            val initialWeightGrams = pObj.optInt("initialWeightGrams", 0)
            val activityLevel = pObj.optString("activityLevel", "").trim()
            val goal = pObj.optString("goal", "").trim()

            if (name.isEmpty()) return BackupValidationResult.Error("El perfil contiene un nombre vacío.")
            if (heightCm <= 0) return BackupValidationResult.Error("Estatura inválida en el perfil: $heightCm cm.")
            if (initialWeightGrams <= 0) return BackupValidationResult.Error("Peso inicial inválido en el perfil: $initialWeightGrams g.")

            ProfileBackupDto(
                name = name,
                preferredName = preferredName.ifEmpty { name },
                heightCm = heightCm,
                initialWeightGrams = initialWeightGrams,
                activityLevel = activityLevel.ifEmpty { "moderada" },
                goal = goal.ifEmpty { "fuerza" },
                createdAtMillis = pObj.optLong("createdAtMillis", exportedAtMillis),
                updatedAtMillis = pObj.optLong("updatedAtMillis", exportedAtMillis)
            )
        } else {
            null
        }

        // WeighIns validation
        val weighInsList = mutableListOf<WeighInBackupDto>()
        if (root.has("weighIns")) {
            val arr = root.optJSONArray("weighIns")
                ?: return BackupValidationResult.Error("El campo 'weighIns' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Elemento de pesaje $i inválido.")
                val measuredAt = item.optLong("measuredAtMillis", 0L)
                val weightGrams = item.optInt("weightGrams", 0)
                if (measuredAt <= 0L || weightGrams <= 0) {
                    return BackupValidationResult.Error("Pesaje en posición $i tiene valores no válidos (fecha o peso <= 0).")
                }
                weighInsList.add(
                    WeighInBackupDto(
                        id = item.optLong("id", 0L),
                        measuredAtMillis = measuredAt,
                        weightGrams = weightGrams,
                        note = item.optString("note", "")
                    )
                )
            }
        }

        // Exercises validation
        val exercisesList = mutableListOf<ExerciseBackupDto>()
        if (root.has("exercises")) {
            val arr = root.optJSONArray("exercises")
                ?: return BackupValidationResult.Error("El campo 'exercises' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Ejercicio en posición $i inválido.")
                val displayName = item.optString("displayName", "").trim()
                val normalizedName = item.optString("normalizedName", "").trim()
                if (displayName.isEmpty() || normalizedName.isEmpty()) {
                    return BackupValidationResult.Error("Ejercicio en posición $i no tiene nombre válido.")
                }
                exercisesList.add(
                    ExerciseBackupDto(
                        id = item.optLong("id", 0L),
                        displayName = displayName,
                        normalizedName = normalizedName,
                        muscleGroup = item.optString("muscleGroup", "General"),
                        secondaryMuscleGroups = item.optString("secondaryMuscleGroups", ""),
                        equipment = if (item.isNull("equipment")) null else item.optString("equipment"),
                        exerciseType = item.optString("exerciseType", "strength"),
                        status = item.optString("status", "CONFIRMED"),
                        createdAtMillis = item.optLong("createdAtMillis", exportedAtMillis)
                    )
                )
            }
        }

        // Exercise Aliases validation
        val aliasesList = mutableListOf<ExerciseAliasBackupDto>()
        if (root.has("exerciseAliases")) {
            val arr = root.optJSONArray("exerciseAliases")
                ?: return BackupValidationResult.Error("El campo 'exerciseAliases' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Alias en posición $i inválido.")
                val alias = item.optString("normalizedAlias", "").trim()
                if (alias.isEmpty()) {
                    return BackupValidationResult.Error("Alias en posición $i está vacío.")
                }
                aliasesList.add(
                    ExerciseAliasBackupDto(
                        id = item.optLong("id", 0L),
                        exerciseId = item.optLong("exerciseId", 0L),
                        normalizedAlias = alias,
                        createdAtMillis = item.optLong("createdAtMillis", exportedAtMillis)
                    )
                )
            }
        }

        // Workout Sessions validation
        val sessionsList = mutableListOf<WorkoutSessionBackupDto>()
        if (root.has("workoutSessions")) {
            val arr = root.optJSONArray("workoutSessions")
                ?: return BackupValidationResult.Error("El campo 'workoutSessions' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Sesión en posición $i inválida.")
                val title = item.optString("title", "").trim()
                val startedAt = item.optLong("startedAtMillis", 0L)
                if (startedAt <= 0L) {
                    return BackupValidationResult.Error("Sesión en posición $i tiene fecha de inicio inválida.")
                }
                sessionsList.add(
                    WorkoutSessionBackupDto(
                        id = item.optLong("id", 0L),
                        title = title.ifEmpty { "Sesión" },
                        startedAtMillis = startedAt,
                        finishedAtMillis = if (item.isNull("finishedAtMillis")) null else item.optLong("finishedAtMillis"),
                        notes = item.optString("notes", "")
                    )
                )
            }
        }

        // Workout Sets validation
        val setsList = mutableListOf<WorkoutSetBackupDto>()
        if (root.has("workoutSets")) {
            val arr = root.optJSONArray("workoutSets")
                ?: return BackupValidationResult.Error("El campo 'workoutSets' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Serie en posición $i inválida.")
                setsList.add(
                    WorkoutSetBackupDto(
                        id = item.optLong("id", 0L),
                        sessionId = item.optLong("sessionId", 0L),
                        exerciseId = item.optLong("exerciseId", 0L),
                        orderInExercise = item.optInt("orderInExercise", 0),
                        executionOrder = item.optInt("executionOrder", 0),
                        loadGrams = item.optInt("loadGrams", 0),
                        repetitions = item.optInt("repetitions", 0),
                        rir = if (item.isNull("rir")) null else item.optInt("rir"),
                        isAssisted = item.optBoolean("isAssisted", false),
                        isDumbbell = item.optBoolean("isDumbbell", false),
                        note = item.optString("note", ""),
                        plannedTarget = if (item.isNull("plannedTarget")) null else item.optString("plannedTarget")
                    )
                )
            }
        }

        // IrisX Memories validation
        val memoriesList = mutableListOf<IrisxMemoryBackupDto>()
        if (root.has("irisxMemories")) {
            val arr = root.optJSONArray("irisxMemories")
                ?: return BackupValidationResult.Error("El campo 'irisxMemories' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Memoria en posición $i inválida.")
                val key = item.optString("sourceKey", "").trim()
                val value = item.optString("targetValue", "").trim()
                if (key.isEmpty() || value.isEmpty()) {
                    return BackupValidationResult.Error("Memoria en posición $i tiene clave o valor vacío.")
                }
                memoriesList.add(
                    IrisxMemoryBackupDto(
                        id = item.optLong("id", 0L),
                        memoryType = item.optString("memoryType", "ALIAS"),
                        sourceKey = key,
                        targetValue = value,
                        confidence = item.optDouble("confidence", 1.0),
                        createdAtMillis = item.optLong("createdAtMillis", exportedAtMillis),
                        updatedAtMillis = item.optLong("updatedAtMillis", exportedAtMillis)
                    )
                )
            }
        }

        // IrisX Conversations validation (optional for backward compatibility with v1 backups)
        val conversationsList = mutableListOf<IrisxConversationBackupDto>()
        if (root.has("irisxConversations") && !root.isNull("irisxConversations")) {
            val arr = root.optJSONArray("irisxConversations")
                ?: return BackupValidationResult.Error("El campo 'irisxConversations' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Conversación en posición $i inválida.")
                conversationsList.add(
                    IrisxConversationBackupDto(
                        id = item.optLong("id", 0L),
                        title = item.optString("title", "Conversación"),
                        activeMuscleGroup = if (item.isNull("activeMuscleGroup")) null else item.optString("activeMuscleGroup"),
                        activeExerciseName = if (item.isNull("activeExerciseName")) null else item.optString("activeExerciseName"),
                        activeSessionId = if (item.isNull("activeSessionId")) null else item.optLong("activeSessionId"),
                        activePeriodScope = if (item.isNull("activePeriodScope")) null else item.optString("activePeriodScope"),
                        createdAtMillis = item.optLong("createdAtMillis", exportedAtMillis),
                        updatedAtMillis = item.optLong("updatedAtMillis", exportedAtMillis)
                    )
                )
            }
        }

        // IrisX Messages validation (optional for backward compatibility with v1 backups)
        val messagesList = mutableListOf<IrisxMessageBackupDto>()
        if (root.has("irisxMessages") && !root.isNull("irisxMessages")) {
            val arr = root.optJSONArray("irisxMessages")
                ?: return BackupValidationResult.Error("El campo 'irisxMessages' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Mensaje en posición $i inválido.")
                val content = item.optString("content", "").trim()
                if (content.isEmpty()) {
                    return BackupValidationResult.Error("Mensaje en posición $i tiene contenido vacío.")
                }
                messagesList.add(
                    IrisxMessageBackupDto(
                        id = item.optLong("id", 0L),
                        conversationId = item.optLong("conversationId", 0L),
                        senderRole = item.optString("senderRole", "USER"),
                        content = content,
                        intentType = if (item.isNull("intentType")) null else item.optString("intentType"),
                        referencedExercise = if (item.isNull("referencedExercise")) null else item.optString("referencedExercise"),
                        referencedMuscleGroup = if (item.isNull("referencedMuscleGroup")) null else item.optString("referencedMuscleGroup"),
                        referencedSessionId = if (item.isNull("referencedSessionId")) null else item.optLong("referencedSessionId"),
                        createdAtMillis = item.optLong("createdAtMillis", exportedAtMillis)
                    )
                )
            }
        }

        // Routines validation (optional for backward compatibility with v1 backups)
        val routinesList = mutableListOf<RoutineBackupDto>()
        if (root.has("routines") && !root.isNull("routines")) {
            val arr = root.optJSONArray("routines")
                ?: return BackupValidationResult.Error("El campo 'routines' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Rutina en posición $i inválida.")
                val name = item.optString("name", "").trim()
                if (name.isEmpty()) {
                    return BackupValidationResult.Error("Rutina en posición $i tiene nombre vacío.")
                }
                routinesList.add(
                    RoutineBackupDto(
                        id = item.optLong("id", 0L),
                        name = name,
                        description = item.optString("description", ""),
                        targetCategory = item.optString("targetCategory", "General"),
                        createdAtMillis = item.optLong("createdAtMillis", exportedAtMillis),
                        updatedAtMillis = item.optLong("updatedAtMillis", exportedAtMillis)
                    )
                )
            }
        }

        // Routine Exercises validation (optional for backward compatibility with v1 backups)
        val routineExercisesList = mutableListOf<RoutineExerciseBackupDto>()
        if (root.has("routineExercises") && !root.isNull("routineExercises")) {
            val arr = root.optJSONArray("routineExercises")
                ?: return BackupValidationResult.Error("El campo 'routineExercises' debe ser una lista.")
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i)
                    ?: return BackupValidationResult.Error("Ejercicio de rutina en posición $i inválido.")
                val exName = item.optString("exerciseName", "").trim()
                if (exName.isEmpty()) {
                    return BackupValidationResult.Error("Ejercicio de rutina en posición $i no tiene nombre.")
                }
                routineExercisesList.add(
                    RoutineExerciseBackupDto(
                        id = item.optLong("id", 0L),
                        routineId = item.optLong("routineId", 0L),
                        exerciseId = if (item.isNull("exerciseId")) null else item.optLong("exerciseId"),
                        exerciseName = exName,
                        orderIndex = item.optInt("orderIndex", i + 1),
                        targetSets = item.optInt("targetSets", 3),
                        minReps = item.optInt("minReps", 8),
                        maxReps = item.optInt("maxReps", 10),
                        suggestedLoadGrams = if (item.isNull("suggestedLoadGrams")) null else item.optInt("suggestedLoadGrams"),
                        isDumbbell = item.optBoolean("isDumbbell", false),
                        isAssisted = item.optBoolean("isAssisted", false),
                        notes = item.optString("notes", "")
                    )
                )
            }
        }

        return BackupValidationResult.Success(
            SyntraBackupPayload(
                formatVersion = formatVersion,
                appVersion = appVersion,
                exportedAtMillis = exportedAtMillis,
                profile = profileDto,
                weighIns = weighInsList,
                exercises = exercisesList,
                exerciseAliases = aliasesList,
                workoutSessions = sessionsList,
                workoutSets = setsList,
                irisxMemories = memoriesList,
                irisxConversations = conversationsList,
                irisxMessages = messagesList,
                routines = routinesList,
                routineExercises = routineExercisesList
            )
        )
    }
}
