package com.syntra.app.neural

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import com.syntra.app.data.AppDatabase
import com.syntra.app.domain.neural.ModelManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Verifies that the lab build is a real Android-level sandbox:
 * different package/UID, independent data directory and independent model storage.
 *
 * It never reads the product application's files; it only checks that they are not
 * reachable from this process.
 */
@RunWith(AndroidJUnit4::class)
class LabIsolationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Assume.assumeTrue(
            "This test only applies to the isolated lab build",
            context.packageName.endsWith(".lab")
        )
    }

    @Test
    fun lab_build_has_independent_package_and_data_directory() {
        val dataDir = context.applicationInfo.dataDir
        val modelsDir = ModelManager.getModelsDirectory(context)

        NeuralLabReport.record(
            context,
            "lab_isolation",
            if (context.packageName == "com.syntra.app.lab" &&
                dataDir.contains("com.syntra.app.lab") &&
                modelsDir.absolutePath.contains("com.syntra.app.lab")
            ) "PASS" else "FAIL",
            mapOf(
                "packageName" to context.packageName,
                "dataDirIsLab" to dataDir.contains("com.syntra.app.lab"),
                "modelsDirIsLab" to modelsDir.absolutePath.contains("com.syntra.app.lab"),
                "dataDir" to dataDir
            )
        )

        assertEquals("com.syntra.app.lab", context.packageName)
        assertTrue("Lab data dir must belong to the lab package: $dataDir", dataDir.contains("com.syntra.app.lab"))
        assertTrue(
            "Model storage must stay inside the lab sandbox: ${modelsDir.absolutePath}",
            modelsDir.absolutePath.contains("com.syntra.app.lab")
        )
    }

    @Test
    fun product_private_directory_is_not_readable_from_lab() {
        val productDataDir = File("/data/data/com.syntra.app")
        val productFiles = File(productDataDir, "files")

        val readable = productFiles.exists() && productFiles.canRead()
        NeuralLabReport.record(
            context,
            "lab_product_sandbox_boundary",
            if (!readable) "PASS" else "INVESTIGATE",
            mapOf(
                "productDataDirExists" to productDataDir.exists(),
                "productFilesReadable" to readable
            )
        )

        assertFalse(
            "The lab process must not be able to read the product app's private files",
            readable
        )
    }
}
