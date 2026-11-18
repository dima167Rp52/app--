package com.filemaster.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

class MainActivity : Activity() {
    private lateinit var rootLayout: LinearLayout
    private lateinit var header: TextView
    private lateinit var pathLabel: TextView
    private lateinit var listArea: LinearLayout
    private lateinit var scroll: ScrollView

    private var rootFolder: DocumentFile? = null
    private var currentFolder: DocumentFile? = null
    private val folderStack = mutableListOf<DocumentFile>()
    private var showingCode = false

    private val bg = 0xFF0B111B.toInt()
    private val panel = 0xFF111B29.toInt()
    private val accent = 0xFF263F5B.toInt()
    private val text = 0xFFE3ECF7.toInt()
    private val muted = 0xFF91A6BE.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        val saved = getPreferences(MODE_PRIVATE).getString("tree_uri", null)
        if (saved != null) {
            try {
                val uri = Uri.parse(saved)
                contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                rootFolder = DocumentFile.fromTreeUri(this, uri)
                currentFolder = rootFolder
                renderFolder()
            } catch (_: Exception) {
                chooseFolder()
            }
        } else {
            chooseFolder()
        }
    }

    private fun buildScreen() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }

        header = TextView(this).apply {
            text = "FileMaster"
            textSize = 27f
            setTextColor(text)
            typeface = Typeface.DEFAULT_BOLD
        }
        rootLayout.addView(header)

        pathLabel = TextView(this).apply {
            text = "Выберите папку"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(7), 0, dp(12))
        }
        rootLayout.addView(pathLabel)

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(makeButton("Выбрать папку") { chooseFolder() }, LinearLayout.LayoutParams(0, dp(46), 1f))
        actions.addView(makeButton("← Назад") { goBack() }, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginStart = dp(8)
        })
        rootLayout.addView(actions)

        scroll = ScrollView(this).apply {
            isFillViewport = true
            setPadding(0, dp(12), 0, 0)
        }
        listArea = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(listArea)
        rootLayout.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(rootLayout)
    }

    private fun makeButton(label: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            setTextColor(text)
            setBackgroundColor(accent)
            setOnClickListener { action() }
        }
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    @Deprecated("Deprecated in Android, kept for broad device compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_FOLDER && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val flags = (data.flags and
                (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            try {
                contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Exception) { }
            getPreferences(MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply()
            val folder = DocumentFile.fromTreeUri(this, uri)
            if (folder == null) {
                toast("Не удалось открыть папку")
                return
            }
            rootFolder = folder
            currentFolder = folder
            folderStack.clear()
            showingCode = false
            renderFolder()
        }
    }

    private fun renderFolder() {
        val folder = currentFolder ?: return
        showingCode = false
        header.text = "FileMaster"
        pathLabel.text = folder.name ?: "Выбранная папка"
        listArea.removeAllViews()

        val entries = try {
            folder.listFiles().sortedWith(
                compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" }
            )
        } catch (_: Exception) {
            toast("Не удалось прочитать содержимое папки")
            emptyList()
        }

        if (entries.isEmpty()) {
            listArea.addView(TextView(this).apply {
                text = "Папка пустая"
                setTextColor(muted)
                textSize = 15f
                setPadding(dp(8), dp(22), dp(8), dp(22))
            })
        }

        entries.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(panel)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val name = TextView(this).apply {
                text = (if (file.isDirectory) "📁  " else "📄  ") + (file.name ?: "Без имени")
                textSize = 16f
                setTextColor(text)
            }
            row.addView(name)
            val details = TextView(this).apply {
                text = if (file.isDirectory) "Папка" else formatSize(file.length())
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(4), 0, 0)
            }
            row.addView(details)
            row.setOnClickListener {
                if (file.isDirectory) {
                    folderStack.add(folder)
                    currentFolder = file
                    renderFolder()
                } else {
                    openTextFile(file)
                }
            }
            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(7)
            listArea.addView(row, params)
        }
    }

    private fun openTextFile(file: DocumentFile) {
        try {
            if (file.length() > MAX_FILE_SIZE) {
                toast("Файл больше 2 МБ — предпросмотр отключён")
                return
            }
            val bytes = contentResolver.openInputStream(file.uri)?.use { it.readBytes() }
                ?: throw IllegalStateException("Не удалось прочитать файл")
            if (bytes.take(8192).any { it == 0.toByte() }) {
                toast("Это похоже на бинарный файл")
                return
            }
            val source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF")
            showCode(file.name ?: "Файл", source)
        } catch (e: Exception) {
            toast("Не удалось открыть файл: ${e.localizedMessage ?: "ошибка чтения"}")
        }
    }

    private fun showCode(fileName: String, source: String) {
        showingCode = true
        header.text = fileName
        pathLabel.text = "Просмотр исходного кода"
        listArea.removeAllViews()
        val code = TextView(this).apply {
            text = source
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFD6DEEB.toInt())
            setBackgroundColor(0xFF101722.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        listArea.addView(code, LinearLayout.LayoutParams(-1, -2))
    }

    private fun goBack() {
        if (showingCode) {
            renderFolder()
            return
        }
        if (folderStack.isNotEmpty()) {
            currentFolder = folderStack.removeAt(folderStack.lastIndex)
            renderFolder()
        } else {
            toast("Это корневая выбранная папка")
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingCode || folderStack.isNotEmpty()) {
            goBack()
        } else {
            super.onBackPressed()
        }
    }

    private fun formatSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes Б"
            bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
            else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
        }
    }

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FOLDER = 410
        private const val MAX_FILE_SIZE = 2L * 1024L * 1024L
    }
}
