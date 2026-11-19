package com.filemaster.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.zip.ZipInputStream

class MainActivity : Activity() {
    private lateinit var rootLayout: LinearLayout
    private lateinit var header: TextView
    private lateinit var pathLabel: TextView
    private lateinit var searchBox: EditText
    private lateinit var listArea: LinearLayout
    private lateinit var scroll: ScrollView

    private var rootFolder: DocumentFile? = null
    private var currentFolder: DocumentFile? = null
    private val folderStack = mutableListOf<DocumentFile>()
    private var showingViewer = false
    private var transferFile: DocumentFile? = null
    private var transferMove = false
    private var allEntries: List<DocumentFile> = emptyList()

    private val bg = 0xFF0A101A.toInt()
    private val panel = 0xFF121E2D.toInt()
    private val accent = 0xFF2864A5.toInt()
    private val textPrimary = 0xFFE8F0FA.toInt()
    private val muted = 0xFF91A6BE.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildScreen()
        if (hasAllFilesAccess()) {
            openSharedStorage()
            return
        }
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
            } catch (_: Exception) { chooseFolder() }
        } else chooseFolder()
    }

    private fun buildScreen() {
        rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(16), dp(14), dp(16), dp(10))
        }
        header = TextView(this).apply {
            text = "FileMaster"
            textSize = 28f
            setTextColor(textPrimary)
            typeface = Typeface.DEFAULT_BOLD
        }
        rootLayout.addView(header)
        pathLabel = TextView(this).apply {
            text = "Твой файловый центр"
            textSize = 13f
            setTextColor(muted)
            setPadding(0, dp(5), 0, dp(12))
        }
        rootLayout.addView(pathLabel)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(makeButton("📁 Папка") { chooseFolder() }, weighted())
        top.addView(makeButton("📄 Открыть файл") { chooseFile() }, weighted(dp(6)))
        rootLayout.addView(top)

        val secondTop = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val secondParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) }
        secondTop.addView(makeButton("＋ Создать папку") { createFolder() }, weighted())
        secondTop.addView(makeButton("Назад") { goBack() }, weighted(dp(6)))
        rootLayout.addView(secondTop, secondParams)

        val accessParams = LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(6) }
        rootLayout.addView(makeButton("🔓 Разрешить доступ ко всем файлам") { requestAllFilesAccess() }, accessParams)

        searchBox = EditText(this).apply {
            hint = "Поиск в этой папке…"
            setHintTextColor(muted)
            setTextColor(textPrimary)
            textSize = 15f
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            setBackgroundColor(panel)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderEntries() }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        val searchParams = LinearLayout.LayoutParams(-1, dp(48))
        searchParams.topMargin = dp(10)
        rootLayout.addView(searchBox, searchParams)

        scroll = ScrollView(this).apply { isFillViewport = true }
        listArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(listArea)
        val scrollParams = LinearLayout.LayoutParams(-1, 0, 1f)
        scrollParams.topMargin = dp(10)
        rootLayout.addView(scroll, scrollParams)
        setContentView(rootLayout)
    }

    private fun weighted(margin: Int = 0) = LinearLayout.LayoutParams(0, dp(44), 1f).apply {
        if (margin > 0) marginStart = margin
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 12f
            setTextColor(textPrimary)
            setBackgroundColor(accent)
            setOnClickListener { action() }
        }

    private fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || hasAllFilesAccess()) {
            openSharedStorage()
            return
        }
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    override fun onResume() {
        super.onResume()
        if (::listArea.isInitialized && hasAllFilesAccess() && rootFolder == null) {
            openSharedStorage()
        }
    }

    private fun openSharedStorage() {
        val folder = DocumentFile.fromFile(Environment.getExternalStorageDirectory())
        rootFolder = folder
        currentFolder = folder
        folderStack.clear()
        transferFile = null
        renderFolder()
        toast("Открыто общее хранилище устройства")
    }

    private fun openInput(file: DocumentFile) =
        if (file.uri.scheme == "file") FileInputStream(File(file.uri.path!!))
        else contentResolver.openInputStream(file.uri)!!

    private fun openOutput(file: DocumentFile) =
        if (file.uri.scheme == "file") FileOutputStream(File(file.uri.path!!))
        else contentResolver.openOutputStream(file.uri)!!

    // Открывает системный выбор одного файла. Это работает даже для файлов
    // в Download, корневую папку которой Android может запрещать выбирать целиком.
    private fun chooseFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FILE)
    }

    private fun chooseFolder() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQUEST_FOLDER)
    }

    @Deprecated("Deprecated in Android, kept for compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK || data?.data == null) return

        val uri = data.data!!
        if (requestCode == REQUEST_FILE) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
            val pickedFile = DocumentFile.fromSingleUri(this, uri)
            if (pickedFile == null) {
                toast("Не удалось открыть выбранный файл")
            } else {
                openFile(pickedFile)
            }
            return
        }

        if (requestCode == REQUEST_FOLDER) {
            val flags = data.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            try { contentResolver.takePersistableUriPermission(uri, flags) } catch (_: Exception) {}
            getPreferences(MODE_PRIVATE).edit().putString("tree_uri", uri.toString()).apply()
            val folder = DocumentFile.fromTreeUri(this, uri) ?: return toast("Не удалось открыть папку")
            rootFolder = folder
            currentFolder = folder
            folderStack.clear()
            transferFile = null
            renderFolder()
        }
    }

    private fun renderFolder() {
        showingViewer = false
        searchBox.visibility = android.view.View.VISIBLE
        header.text = "FileMaster"
        pathLabel.text = currentFolder?.name ?: "Выбери папку"
        searchBox.setText("")
        val folder = currentFolder ?: return
        allEntries = try {
            folder.listFiles().sortedWith(compareBy<DocumentFile> { !it.isDirectory }.thenBy { it.name?.lowercase() ?: "" })
        } catch (_: Exception) {
            toast("Не удалось прочитать папку")
            emptyList()
        }
        renderEntries()
    }

    private fun renderEntries() {
        if (showingViewer) return
        listArea.removeAllViews()
        val query = searchBox.text?.toString()?.trim()?.lowercase().orEmpty()
        val entries = allEntries.filter { query.isEmpty() || (it.name ?: "").lowercase().contains(query) }
        if (transferFile != null) {
            val mode = if (transferMove) "Переместить сюда" else "Вставить копию сюда"
            listArea.addView(makeButton("📥 $mode: ${transferFile?.name ?: "файл"}") { pasteTransfer() })
        }
        if (entries.isEmpty()) {
            listArea.addView(label(if (query.isEmpty()) "Здесь пока пусто" else "Ничего не найдено", muted, 16f))
        }
        entries.forEach { file ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(panel)
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            row.addView(label((if (file.isDirectory) "📁  " else iconFor(file.name) + "  ") + (file.name ?: "Без имени"), textPrimary, 15f))
            row.addView(label(if (file.isDirectory) "Папка" else formatSize(file.length()) + "  •  " + (file.type ?: "Файл"), muted, 11f))
            row.setOnClickListener {
                if (file.isDirectory) {
                    currentFolder?.let { folderStack.add(it) }
                    currentFolder = file
                    renderFolder()
                } else openFile(file)
            }
            row.setOnLongClickListener { showFileActions(file); true }
            val p = LinearLayout.LayoutParams(-1, -2)
            p.bottomMargin = dp(7)
            listArea.addView(row, p)
        }
    }

    private fun iconFor(name: String?): String = when (name?.substringAfterLast('.', "")?.lowercase()) {
        "html", "htm" -> "🌐"
        "apk" -> "📦"
        "zip", "jar" -> "🗜️"
        "png", "jpg", "jpeg", "webp", "gif" -> "🖼️"
        "mp3", "wav", "ogg" -> "🎵"
        "mp4", "mkv" -> "🎬"
        else -> "📄"
    }

    private fun openFile(file: DocumentFile) {
        val ext = file.name?.substringAfterLast('.', "")?.lowercase().orEmpty()
        val zipFile = try {
            openInput(file).use { input -> isZipSignature(readLimited(input, 4)) }
        } catch (_: Exception) { false }
        when {
            zipFile || ext in ARCHIVE_EXTENSIONS -> showArchive(file)
            ext == "html" || ext == "htm" -> showHtmlChoice(file)
            ext in IMAGE_EXTENSIONS -> openWithExternalApp(file)
            else -> showText(file)
        }
    }

    private fun isZipSignature(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            ((bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()) ||
             (bytes[2] == 0x05.toByte() && bytes[3] == 0x06.toByte()) ||
             (bytes[2] == 0x07.toByte() && bytes[3] == 0x08.toByte()))

    private fun showHtmlChoice(file: DocumentFile) {
        android.app.AlertDialog.Builder(this)
            .setTitle(file.name ?: "HTML")
            .setItems(arrayOf("Посмотреть код", "Открыть страницу")) { _, which ->
                if (which == 0) showText(file) else previewHtml(file)
            }.show()
    }

    // Потоковое чтение: не загружаем весь большой файл в память.
    private fun readText(file: DocumentFile): String? {
        val sample = ByteArray(8192)
        val n = openInput(file).use { it.read(sample) }
        if (n > 0 && (0 until n).any { sample[it] == 0.toByte() }) return null
        val result = StringBuilder()
        var truncated = false
        InputStreamReader(openInput(file), Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8192)
            while (result.length < MAX_PREVIEW_CHARS) {
                val count = reader.read(buffer, 0, minOf(buffer.size, MAX_PREVIEW_CHARS - result.length))
                if (count < 0) break
                result.append(buffer, 0, count)
                if (result.length >= MAX_PREVIEW_CHARS) {
                    truncated = reader.read() != -1
                    break
                }
            }
        }
        return result.toString().removePrefix("\uFEFF") +
            if (truncated) "\n\n…Показана начальная часть большого файла." else ""
    }

    private fun showText(file: DocumentFile) {
        val source = try { readText(file) } catch (_: Exception) {
            toast("Не удалось прочитать файл")
            null
        }
        if (source == null) {
            showHex(file)
            return
        }
        showViewer(file.name ?: "Просмотр файла", "Текстовый просмотр • потоковое чтение", source)
    }

    private fun previewHtml(file: DocumentFile) {
        val html = try { readText(file) } catch (_: Exception) { null } ?: return
        showingViewer = true
        searchBox.visibility = android.view.View.GONE
        header.text = file.name ?: "HTML"
        pathLabel.text = "Предпросмотр HTML • JavaScript выключен"
        listArea.removeAllViews()
        val web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            webViewClient = WebViewClient()
            loadDataWithBaseURL(null, html, "text/html", "UTF-8", null)
        }
        listArea.addView(web, LinearLayout.LayoutParams(-1, -1))
    }

    private fun showArchive(file: DocumentFile) {
        showingViewer = true
        searchBox.visibility = android.view.View.GONE
        header.text = file.name ?: "Архив"
        pathLabel.text = "Содержимое архива • нажми на файл для просмотра"
        listArea.removeAllViews()
        try {
            val entries = mutableListOf<String>()
            ZipInputStream(openInput(file)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && entries.size < MAX_ARCHIVE_ENTRIES) {
                    entries.add(entry.name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (entries.isEmpty()) {
                listArea.addView(label("Архив пуст или формат не поддерживается", muted, 14f))
                return
            }
            entries.forEach { entryName ->
                val row = label((if (entryName.endsWith("/")) "📁  " else "📄  ") + entryName, textPrimary, 12f).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(panel)
                    setOnClickListener { openArchiveEntry(file, entryName) }
                }
                val params = LinearLayout.LayoutParams(-1, -2)
                params.bottomMargin = dp(5)
                listArea.addView(row, params)
            }
            if (entries.size == MAX_ARCHIVE_ENTRIES) {
                listArea.addView(label("Показаны первые " + MAX_ARCHIVE_ENTRIES + " элементов", muted, 11f))
            }
        } catch (_: Exception) {
            listArea.addView(label("Не удалось прочитать архив. Возможно, он повреждён или защищён паролем.", muted, 14f))
        }
    }

    private fun openArchiveEntry(archive: DocumentFile, entryName: String) {
        if (entryName.endsWith("/")) return
        try {
            ZipInputStream(openInput(archive)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        val prefix = readLimited(zip, 4)
                        val bytes = if (isZipSignature(prefix)) {
                            prefix + readLimited(zip, MAX_NESTED_ARCHIVE_BYTES - prefix.size)
                        } else {
                            prefix + readLimited(zip, MAX_ARCHIVE_PREVIEW_BYTES - prefix.size)
                        }
                        displayArchiveEntry(entryName, bytes)
                        return
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            toast("Файл внутри архива не найден")
        } catch (_: Exception) {
            toast("Не удалось открыть файл из архива")
        }
    }

    private fun displayArchiveEntry(entryName: String, bytes: ByteArray) {
        val ext = entryName.substringAfterLast('.', "").lowercase()
        if (isZipSignature(bytes)) {
            showArchiveBytes(entryName.substringAfterLast('/'), bytes)
        } else if (ext in IMAGE_EXTENSIONS) {
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            if (bitmap != null) {
                showingViewer = true
                searchBox.visibility = android.view.View.GONE
                header.text = entryName.substringAfterLast('/')
                pathLabel.text = "Изображение внутри архива"
                listArea.removeAllViews()
                val image = ImageView(this).apply {
                    setImageBitmap(bitmap)
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }
                listArea.addView(image, LinearLayout.LayoutParams(-1, -1))
            } else showArchiveHex(entryName, bytes)
        } else if (ext in BINARY_EXTENSIONS || bytes.take(minOf(8192, bytes.size)).any { it == 0.toByte() }) {
            showArchiveHex(entryName, bytes)
        } else {
            val source = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF") +
                if (bytes.size >= MAX_ARCHIVE_PREVIEW_BYTES) "\n\n…Показана начальная часть файла." else ""
            showViewer(entryName.substringAfterLast('/'), "Файл внутри архива", source)
        }
    }

    private fun showArchiveBytes(title: String, bytes: ByteArray) {
        showingViewer = true
        searchBox.visibility = android.view.View.GONE
        header.text = title
        pathLabel.text = "Вложенный архив • нажми на файл для просмотра"
        listArea.removeAllViews()
        try {
            val names = mutableListOf<String>()
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null && names.size < MAX_ARCHIVE_ENTRIES) {
                    names.add(entry.name)
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
            if (names.isEmpty()) {
                listArea.addView(label("Архив пуст или не удалось прочитать его содержимое", muted, 14f))
                return
            }
            names.forEach { name ->
                val row = label((if (name.endsWith("/")) "📁  " else "📄  ") + name, textPrimary, 12f).apply {
                    typeface = Typeface.MONOSPACE
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                    setBackgroundColor(panel)
                    setOnClickListener { openArchiveBytesEntry(bytes, name) }
                }
                val params = LinearLayout.LayoutParams(-1, -2)
                params.bottomMargin = dp(5)
                listArea.addView(row, params)
            }
        } catch (_: Exception) {
            listArea.addView(label("Не удалось прочитать вложенный архив", muted, 14f))
        }
    }

    private fun openArchiveBytesEntry(archiveBytes: ByteArray, entryName: String) {
        if (entryName.endsWith("/")) return
        try {
            ZipInputStream(ByteArrayInputStream(archiveBytes)).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (entry.name == entryName) {
                        val prefix = readLimited(zip, 4)
                        val bytes = if (isZipSignature(prefix)) {
                            prefix + readLimited(zip, MAX_NESTED_ARCHIVE_BYTES - prefix.size)
                        } else {
                            prefix + readLimited(zip, MAX_ARCHIVE_PREVIEW_BYTES - prefix.size)
                        }
                        displayArchiveEntry(entryName, bytes)
                        return
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {
            toast("Не удалось открыть файл из вложенного архива")
        }
    }

    private fun showArchiveHex(name: String, bytes: ByteArray) {
        val lines = StringBuilder()
        for (start in bytes.indices step 16) {
            val end = minOf(start + 16, bytes.size)
            lines.append("%08X  ".format(start))
            for (i in start until end) lines.append("%02X ".format(bytes[i].toInt() and 0xFF))
            lines.append("  ")
            for (i in start until end) {
                val c = bytes[i].toInt() and 0xFF
                lines.append(if (c in 32..126) c.toChar() else '.')
            }
            lines.append('\n')
        }
        showViewer(name.substringAfterLast('/'), "Бинарный файл • HEX", lines.toString())
    }

    private fun showHex(file: DocumentFile) {
        try {
            val bytes = openInput(file).use { readLimited(it, MAX_HEX_BYTES) }
            showArchiveHex(file.name ?: "Бинарный файл", bytes)
        } catch (_: Exception) { toast("Не удалось прочитать файл") }
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (total < limit) {
            val count = input.read(buffer, 0, minOf(buffer.size, limit - total))
            if (count < 0) break
            output.write(buffer, 0, count)
            total += count
        }
        return output.toByteArray()
    }
    private fun showViewer(title: String, subtitle: String, source: String) {
        showingViewer = true
        searchBox.visibility = android.view.View.GONE
        header.text = title
        pathLabel.text = subtitle
        listArea.removeAllViews()
        val code = TextView(this).apply {
            text = highlightCode(source, title)
            textSize = 12.5f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFD6DEEB.toInt())
            setBackgroundColor(0xFF101722.toInt())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setTextIsSelectable(true)
        }
        listArea.addView(code, LinearLayout.LayoutParams(-1, -2))
    }

    private fun highlightCode(source: String, fileName: String): SpannableString {
        val styled = SpannableString(source)
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val keywords = when (ext) {
            "html", "htm" -> listOf("html", "head", "body", "div", "span", "script", "style", "title", "meta", "link", "h1", "p")
            "py" -> listOf("def", "class", "return", "if", "else", "elif", "for", "while", "import", "from", "as", "True", "False", "None")
            "kt", "kts", "java" -> listOf("fun", "val", "var", "class", "object", "return", "if", "else", "when", "for", "while", "import", "package", "private", "public", "override")
            "js", "ts", "css" -> listOf("const", "let", "var", "function", "return", "if", "else", "class", "new", "import", "export")
            else -> listOf("true", "false", "null", "if", "else", "return", "class", "function")
        }
        val pattern = Regex("\\b(" + keywords.joinToString("|") { Regex.escape(it) } + ")\\b")
        pattern.findAll(source).forEach {
            styled.setSpan(ForegroundColorSpan(0xFF79B8FF.toInt()), it.range.first, it.range.last + 1, 0)
        }
        Regex("""("[^"]*"|'[^']*')""").findAll(source).forEach {
            styled.setSpan(ForegroundColorSpan(0xFFA5D6A7.toInt()), it.range.first, it.range.last + 1, 0)
        }
        return styled
    }

    private fun createFolder() {
        val input = EditText(this).apply { hint = "Название папки"; setTextColor(textPrimary) }
        android.app.AlertDialog.Builder(this).setTitle("Новая папка").setView(input)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Создать") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && currentFolder?.findFile(name) == null) {
                    if (currentFolder?.createDirectory(name) != null) renderFolder() else toast("Не удалось создать папку")
                } else toast("Укажи другое название")
            }.show()
    }

    private fun showFileActions(file: DocumentFile) {
        val actions = if (file.isDirectory) arrayOf("Переименовать", "Копировать сюда", "Переместить сюда", "Удалить")
            else arrayOf("Переименовать", "Копировать сюда", "Переместить сюда", "Удалить")
        android.app.AlertDialog.Builder(this).setTitle(file.name ?: "Файл")
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> renameFile(file)
                    1 -> { transferFile = file; transferMove = false; renderEntries(); toast("Открой папку назначения и нажми кнопку вставки") }
                    2 -> { transferFile = file; transferMove = true; renderEntries(); toast("Открой папку назначения и нажми кнопку вставки") }
                    3 -> confirmDelete(file)
                }
            }.show()
    }

    private fun renameFile(file: DocumentFile) {
        val input = EditText(this).apply { setText(file.name ?: ""); setTextColor(textPrimary) }
        android.app.AlertDialog.Builder(this).setTitle("Переименовать").setView(input)
            .setNegativeButton("Отмена", null).setPositiveButton("Сохранить") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && file.renameTo(name)) renderFolder() else toast("Не удалось переименовать")
            }.show()
    }

    private fun confirmDelete(file: DocumentFile) {
        android.app.AlertDialog.Builder(this).setTitle("Удалить?")
            .setMessage("Удалить «${file.name}»?")
            .setNegativeButton("Отмена", null).setPositiveButton("Удалить") { _, _ ->
                if (file.delete()) renderFolder() else toast("Не удалось удалить")
            }.show()
    }

    private fun pasteTransfer() {
        val source = transferFile ?: return
        val destination = currentFolder ?: return
        if (source.uri == destination.uri || source.name == null) return toast("Нельзя вставить сюда")
        val targetName = source.name!!
        if (destination.findFile(targetName) != null) return toast("Файл с таким именем уже есть")
        try {
            val target = if (source.isDirectory) destination.createDirectory(targetName)
                else destination.createFile(source.type ?: "application/octet-stream", targetName)
            if (target == null) return toast("Не удалось создать копию")
            if (source.isDirectory) {
                source.listFiles().forEach { child -> copyOne(child, target) }
            } else {
                openInput(source).use { input -> openOutput(target).use { output -> input.copyTo(output) } }
            }
            if (transferMove) source.delete()
            transferFile = null
            renderFolder()
            toast(if (transferMove) "Перемещено" else "Скопировано")
        } catch (_: Exception) { toast("Не удалось выполнить операцию") }
    }

    private fun copyOne(source: DocumentFile, destination: DocumentFile) {
        val name = source.name ?: return
        if (source.isDirectory) {
            val folder = destination.createDirectory(name) ?: return
            source.listFiles().forEach { copyOne(it, folder) }
        } else {
            val target = destination.createFile(source.type ?: "application/octet-stream", name) ?: return
            openInput(source).use { input -> openOutput(target).use { output -> input.copyTo(output) } }
        }
    }

    private fun openWithExternalApp(file: DocumentFile) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(file.uri, file.type ?: "*/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) { toast("Нет приложения для просмотра этого файла") }
    }

    private fun goBack() {
        if (showingViewer) { renderFolder(); return }
        if (folderStack.isNotEmpty()) {
            currentFolder = folderStack.removeAt(folderStack.lastIndex)
            renderFolder()
        } else toast("Это корневая выбранная папка")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (showingViewer || folderStack.isNotEmpty()) goBack() else super.onBackPressed()
    }

    private fun label(value: String, color: Int, size: Float) = TextView(this).apply {
        text = value
        setTextColor(color)
        textSize = size
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format("%.1f МБ", bytes / (1024.0 * 1024.0))
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_FOLDER = 410
        private const val REQUEST_FILE = 411
        private const val MAX_PREVIEW_CHARS = 1_500_000
        private const val MAX_HEX_BYTES = 64 * 1024
        private const val MAX_ARCHIVE_PREVIEW_BYTES = 2 * 1024 * 1024
        private const val MAX_NESTED_ARCHIVE_BYTES = 32 * 1024 * 1024
        private const val MAX_ARCHIVE_ENTRIES = 1500
        private val ARCHIVE_EXTENSIONS = setOf("zip", "apk", "jar", "mcpack", "mcaddon")
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
        private val BINARY_EXTENSIONS = setOf("dex", "so", "prof", "profm", "class", "jar")
    }
}
