package com.syntra.app.ui.categories

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.syntra.app.data.AppDatabase
import com.syntra.app.data.CategoryEntity
import kotlinx.coroutines.launch

@Composable
fun ManageCategoriesDialog(
    db: AppDatabase,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val categories by remember(db) { db.categoryDao().observeAll() }.collectAsState(initial = emptyList())

    var showAddRow by remember { mutableStateOf(false) }
    var newCatName by remember { mutableStateOf("") }
    var newCatIcon by remember { mutableStateOf("📁") }
    var categoryToEdit by remember { mutableStateOf<CategoryEntity?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF141414)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Categorías de entrenamiento",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Organizá tus días y rutinas (Pecho, Espalda, etc.)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Text("✕", color = Color.Gray, style = MaterialTheme.typography.titleMedium)
                    }
                }

                if (!showAddRow) {
                    Button(
                        onClick = { showAddRow = true },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("+ Nueva categoría", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                } else {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text("Crear nueva categoría", style = MaterialTheme.typography.titleSmall, color = Color.White)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = newCatIcon,
                                    onValueChange = { newCatIcon = it },
                                    label = { Text("Ícono") },
                                    modifier = Modifier.width(80.dp),
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = newCatName,
                                    onValueChange = { newCatName = it },
                                    label = { Text("Nombre (ej. Hombros)") },
                                    modifier = Modifier.weight(1f),
                                    singleLine = true
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(onClick = { showAddRow = false }) {
                                    Text("Cancelar", color = Color.Gray)
                                }
                                Button(
                                    onClick = {
                                        if (newCatName.isNotBlank()) {
                                            scope.launch {
                                                db.categoryDao().insert(
                                                    CategoryEntity(
                                                        name = newCatName.trim(),
                                                        icon = newCatIcon.trim().ifEmpty { "📁" },
                                                        orderIndex = categories.size
                                                    )
                                                )
                                                newCatName = ""
                                                newCatIcon = "📁"
                                                showAddRow = false
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Agregar", color = Color.Black, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(categories, key = { it.id }) { cat ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (cat.isHidden) Color(0xFF181818) else Color(0xFF1E1E1E)
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                                ) {
                                    Text(text = cat.icon, style = MaterialTheme.typography.titleMedium)
                                    Column {
                                        Text(
                                            text = cat.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold,
                                            color = if (cat.isHidden) Color.Gray else Color.White
                                        )
                                        if (cat.isHidden) {
                                            Text("Oculta", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                        }
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                db.categoryDao().update(cat.copy(isHidden = !cat.isHidden))
                                            }
                                        }
                                    ) {
                                        Text(if (cat.isHidden) "👁" else "🚫", color = Color.LightGray)
                                    }
                                }
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    OutlinedButton(onClick = onDismiss) {
                        Text("Cerrar", color = Color.White)
                    }
                }
            }
        }
    }
}
