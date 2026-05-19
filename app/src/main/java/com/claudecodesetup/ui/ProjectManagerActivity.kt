package com.claudecodesetup.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.claudecodesetup.TerminalActivity
import com.claudecodesetup.data.AppPreferences
import com.claudecodesetup.managers.NodeBridgeManager
import java.io.File

class ProjectManagerActivity : ComponentActivity() {

    private var pendingFolderCallback: ((String) -> Unit)? = null

    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            val path = treeUriToPath(treeUri)
            if (path != null) pendingFolderCallback?.invoke(path)
            pendingFolderCallback = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = AppPreferences(this)
        setContent {
            ProjectManagerScreen(
                prefs = prefs,
                onPickFolder = { callback ->
                    pendingFolderCallback = callback
                    folderPicker.launch(null)
                },
                onOpenProject = { project ->
                    if (!File(project.path).exists()) {
                        Toast.makeText(this, "Folder not found: ${project.path}", Toast.LENGTH_LONG).show()
                    } else {
                        prefs.setProjectPath(project.path)
                        if (project.systemPrompt.isNotEmpty())
                            prefs.setCustomSystemPrompt(project.systemPrompt)
                        NodeBridgeManager(this).refreshConfig(prefs)
                        // Bring existing TerminalActivity to front (or create one) and deliver
                        // the project path via onNewIntent to open a new session tab.
                        val intent = Intent(this, TerminalActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                            putExtra(TerminalActivity.EXTRA_PROJECT_PATH, project.path)
                        }
                        startActivity(intent)
                    }
                },
                onBack = { finish() }
            )
        }
    }

    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val colon = docId.indexOf(':')
            if (colon < 0) return null
            val volume = docId.substring(0, colon)
            val rel    = docId.substring(colon + 1)
            if (volume.equals("primary", ignoreCase = true)) {
                "/storage/emulated/0/$rel"
            } else {
                "/storage/$volume/$rel"
            }
        } catch (_: Exception) { null }
    }
}
