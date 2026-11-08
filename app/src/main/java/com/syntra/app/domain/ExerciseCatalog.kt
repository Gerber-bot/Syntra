package com.syntra.app.domain

import com.syntra.app.data.ExerciseEntity

data class CatalogExercise(
    val displayName: String,
    val normalizedName: String,
    val muscleGroup: String,
    val secondaryMuscleGroups: String,
    val equipment: String?,
    val exerciseType: String = "strength",
    val aliases: List<String> = emptyList()
)

object ExerciseCatalog {

    val DEFAULT_CATALOG: List<CatalogExercise> = listOf(
        CatalogExercise(
            displayName = "Sentadilla",
            normalizedName = "sentadilla",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Glúteos, Core",
            equipment = "Barra",
            aliases = listOf("squat", "back squat", "sentadillas")
        ),
        CatalogExercise(
            displayName = "Press de banca",
            normalizedName = "press de banca",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Tríceps, Deltoides anterior",
            equipment = "Barra",
            aliases = listOf("bench press", "press banca", "banca plana")
        ),
        CatalogExercise(
            displayName = "Peso muerto",
            normalizedName = "peso muerto",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Isquiosurales, Glúteos",
            equipment = "Barra",
            aliases = listOf("deadlift", "peso muerto convencional")
        ),
        CatalogExercise(
            displayName = "Peso muerto rumano",
            normalizedName = "peso muerto rumano",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Isquiosurales, Glúteos",
            equipment = "Barra",
            aliases = listOf("rdl", "romanian deadlift", "rumano")
        ),
        CatalogExercise(
            displayName = "Remo con barra",
            normalizedName = "remo con barra",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Bíceps, Core",
            equipment = "Barra",
            aliases = listOf("barbell row", "remo barra")
        ),
        CatalogExercise(
            displayName = "Press militar",
            normalizedName = "press militar",
            muscleGroup = "Hombros",
            secondaryMuscleGroups = "Tríceps, Core",
            equipment = "Barra",
            aliases = listOf("ohp", "overhead press", "press hombros")
        ),
        CatalogExercise(
            displayName = "Dominadas",
            normalizedName = "dominadas",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Bíceps, Core",
            equipment = "Peso corporal",
            aliases = listOf("pull ups", "pullups", "dominada")
        ),
        CatalogExercise(
            displayName = "Fondos",
            normalizedName = "fondos",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Tríceps, Hombros",
            equipment = "Paralelas",
            aliases = listOf("dips", "fondos en paralelas")
        ),
        CatalogExercise(
            displayName = "Hip thrust",
            normalizedName = "hip thrust",
            muscleGroup = "Glúteos",
            secondaryMuscleGroups = "Isquiosurales",
            equipment = "Barra",
            aliases = listOf("empuje de cadera", "puente de gluteo con barra")
        ),
        CatalogExercise(
            displayName = "Prensa de piernas",
            normalizedName = "prensa de piernas",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Cuádriceps, Glúteos",
            equipment = "Máquina",
            aliases = listOf("prensa", "leg press")
        ),
        CatalogExercise(
            displayName = "Elevaciones laterales",
            normalizedName = "elevaciones laterales",
            muscleGroup = "Hombros",
            secondaryMuscleGroups = "Trapecio",
            equipment = "Mancuernas",
            aliases = listOf("vuelos laterales", "lateral raises")
        ),
        CatalogExercise(
            displayName = "Curl de bíceps",
            normalizedName = "curl de biceps",
            muscleGroup = "Bíceps",
            secondaryMuscleGroups = "Antebrazo",
            equipment = "Mancuernas",
            aliases = listOf("bicep curl", "curl mancuerna")
        ),
        CatalogExercise(
            displayName = "Extensiones de tríceps",
            normalizedName = "extensiones de triceps",
            muscleGroup = "Tríceps",
            secondaryMuscleGroups = "Pecho",
            equipment = "Polea",
            aliases = listOf("tricep pushdown", "tiron triceps")
        ),
        CatalogExercise(
            displayName = "Buenos días",
            normalizedName = "buenos dias",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Cadena posterior, Isquiosurales, Glúteos",
            equipment = "Barra",
            aliases = listOf("good mornings", "good morning")
        )
    )

    fun toEntities(): List<ExerciseEntity> = DEFAULT_CATALOG.map { item ->
        ExerciseEntity(
            displayName = item.displayName,
            normalizedName = item.normalizedName,
            muscleGroup = item.muscleGroup,
            secondaryMuscleGroups = item.secondaryMuscleGroups,
            equipment = item.equipment,
            exerciseType = item.exerciseType,
            status = "CONFIRMED"
        )
    }
}
