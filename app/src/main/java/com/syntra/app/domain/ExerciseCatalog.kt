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
            aliases = listOf("squat", "back squat", "sentadillas", "sentadilla con barra")
        ),
        CatalogExercise(
            displayName = "Press de banca",
            normalizedName = "press de banca",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Tríceps, Deltoides anterior",
            equipment = "Barra",
            aliases = listOf("bench press", "press banca", "banca plana", "banco plano", "press plano")
        ),
        CatalogExercise(
            displayName = "Press inclinado con mancuernas",
            normalizedName = "press inclinado con mancuernas",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Deltoides anterior, Tríceps",
            equipment = "Mancuernas",
            aliases = listOf("inclinado con mancuernas", "inclinado mancuernas", "press inclinado", "inclinado", "press inclinado mancuerna")
        ),
        CatalogExercise(
            displayName = "Press de pecho en máquina / Hammer",
            normalizedName = "press de pecho en maquina hammer",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Tríceps, Deltoides anterior",
            equipment = "Máquina",
            aliases = listOf("inclinado hammer", "hammer pecho", "press hammer", "inclinado maquina", "pecho hammer", "chest press")
        ),
        CatalogExercise(
            displayName = "Cruce de poleas",
            normalizedName = "cruce de poleas",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Deltoides anterior",
            equipment = "Polea",
            aliases = listOf("cruce de polea", "cruces de poleas", "poleas cruzadas", "cruces en polea", "cable crossover", "cable fly")
        ),
        CatalogExercise(
            displayName = "Aperturas con mancuernas",
            normalizedName = "aperturas con mancuernas",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Deltoides anterior",
            equipment = "Mancuernas",
            aliases = listOf("aperturas", "flyes", "apertura con mancuernas", "apertura plana")
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
            displayName = "Jalón al pecho en polea",
            normalizedName = "jalon al pecho en polea",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Bíceps, Deltoides posterior",
            equipment = "Polea",
            aliases = listOf("jalon al pecho", "polea al pecho", "lat pulldown", "jalon polea", "tiron al pecho")
        ),
        CatalogExercise(
            displayName = "Remo en polea baja / Gironda",
            normalizedName = "remo en polea baja gironda",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Bíceps, Espalda media",
            equipment = "Polea",
            aliases = listOf("remo gironda", "remo en polea", "seated cable row", "remo polea baja")
        ),
        CatalogExercise(
            displayName = "Remo con mancuerna",
            normalizedName = "remo con mancuerna",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Bíceps",
            equipment = "Mancuernas",
            aliases = listOf("serrucho", "remo mancuerna", "dumbbell row")
        ),
        CatalogExercise(
            displayName = "Press militar",
            normalizedName = "press militar",
            muscleGroup = "Hombros",
            secondaryMuscleGroups = "Tríceps, Core",
            equipment = "Barra",
            aliases = listOf("ohp", "overhead press", "press hombros", "militar con barra")
        ),
        CatalogExercise(
            displayName = "Elevaciones laterales",
            normalizedName = "elevaciones laterales",
            muscleGroup = "Hombros",
            secondaryMuscleGroups = "Trapecio",
            equipment = "Mancuernas",
            aliases = listOf("vuelos laterales", "lateral raises", "vuelo lateral", "elevacion lateral", "vuelos con mancuernas")
        ),
        CatalogExercise(
            displayName = "Pájaros / Vuelos posteriores",
            normalizedName = "pajaros vuelos posteriores",
            muscleGroup = "Hombros",
            secondaryMuscleGroups = "Trapecio, Espalda alta",
            equipment = "Mancuernas",
            aliases = listOf("pajaros", "pájaros", "vuelos posteriores", "rear delt fly", "deltoides posterior")
        ),
        CatalogExercise(
            displayName = "Dominadas",
            normalizedName = "dominadas",
            muscleGroup = "Espalda",
            secondaryMuscleGroups = "Bíceps, Core",
            equipment = "Peso corporal",
            aliases = listOf("pull ups", "pullups", "dominada", "chin ups")
        ),
        CatalogExercise(
            displayName = "Fondos",
            normalizedName = "fondos",
            muscleGroup = "Pecho",
            secondaryMuscleGroups = "Tríceps, Hombros",
            equipment = "Paralelas",
            aliases = listOf("dips", "fondos en paralelas", "fondos de pecho")
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
            aliases = listOf("prensa", "leg press", "prensa 45")
        ),
        CatalogExercise(
            displayName = "Extensión de cuádriceps en máquina",
            normalizedName = "extension de cuadriceps en maquina",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Cuádriceps",
            equipment = "Máquina",
            aliases = listOf("camilla adelante", "sillon de cuadriceps", "sillón de cuadriceps", "extension cuadriceps", "extensiones cuadriceps", "leg extension")
        ),
        CatalogExercise(
            displayName = "Curl femoral acostado",
            normalizedName = "curl femoral acostado",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Isquiosurales",
            equipment = "Máquina",
            aliases = listOf("camilla atras", "camilla femoral", "curl femoral", "leg curl", "femoral acostado")
        ),
        CatalogExercise(
            displayName = "Sentadilla búlgara",
            normalizedName = "sentadilla bulgara",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Glúteos, Cuádriceps",
            equipment = "Mancuernas",
            aliases = listOf("bulgaras", "búlgaras", "sentadillas bulgaras", "split squat")
        ),
        CatalogExercise(
            displayName = "Gemelos en máquina",
            normalizedName = "gemelos en maquina",
            muscleGroup = "Piernas",
            secondaryMuscleGroups = "Sóleo",
            equipment = "Máquina",
            aliases = listOf("pantorrillas", "gemelos", "elevacion de talones", "calf raise")
        ),
        CatalogExercise(
            displayName = "Curl de bíceps",
            normalizedName = "curl de biceps",
            muscleGroup = "Bíceps",
            secondaryMuscleGroups = "Antebrazo",
            equipment = "Mancuernas",
            aliases = listOf("bicep curl", "curl mancuerna", "curl con mancuernas")
        ),
        CatalogExercise(
            displayName = "Curl de bíceps en banco Scott",
            normalizedName = "curl de biceps en banco scott",
            muscleGroup = "Bíceps",
            secondaryMuscleGroups = "Braquial, Antebrazo",
            equipment = "Barra",
            aliases = listOf("banco scott", "predicador", "curl scott", "curl predicador", "scott con barra w")
        ),
        CatalogExercise(
            displayName = "Curl martillo",
            normalizedName = "curl martillo",
            muscleGroup = "Bíceps",
            secondaryMuscleGroups = "Braquial, Antebrazo",
            equipment = "Mancuernas",
            aliases = listOf("martillo", "hammer curl", "curl martillo mancuernas")
        ),
        CatalogExercise(
            displayName = "Extensiones de tríceps en polea",
            normalizedName = "extensiones de triceps en polea",
            muscleGroup = "Tríceps",
            secondaryMuscleGroups = "Antebrazo",
            equipment = "Polea",
            aliases = listOf("triceps con triangulo", "triceps triangulo", "triceps con soga", "triceps cuerda", "polea triceps triangulo", "tiron triceps con triangulo", "tricep pushdown", "tiron triceps")
        ),
        CatalogExercise(
            displayName = "Press francés / A la frente",
            normalizedName = "press frances a la frente",
            muscleGroup = "Tríceps",
            secondaryMuscleGroups = "Antebrazo",
            equipment = "Barra",
            aliases = listOf("a la frente con w", "a la frente", "press frances", "press frances con w", "rompecraneos", "skull crusher")
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
