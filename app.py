import os
import sys
import shutil
from pathlib import Path

from PySide6.QtCore import Qt, QDir, QSortFilterProxyModel
from PySide6.QtGui import QAction, QColor, QFont, QSyntaxHighlighter, QTextCharFormat
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QSplitter,
    QTreeView, QFileSystemModel, QPlainTextEdit, QLineEdit, QPushButton,
    QLabel, QToolBar, QFileDialog, QMessageBox, QInputDialog, QMenu, QTabWidget,
    QStatusBar, QFrame, QAbstractItemView
)

APP_NAME = "FileMaster"
TEXT_EXTENSIONS = {
    ".py", ".pyw", ".js", ".jsx", ".ts", ".tsx", ".html", ".htm", ".css",
    ".scss", ".json", ".xml", ".yml", ".yaml", ".toml", ".ini", ".cfg",
    ".txt", ".md", ".rst", ".java", ".kt", ".kts", ".c", ".h", ".cpp",
    ".hpp", ".cs", ".go", ".rs", ".php", ".rb", ".sh", ".bat", ".cmd",
    ".ps1", ".sql", ".properties", ".gradle", ".gitignore", ".env",
    ".log", ".svg", ".csv", ".manifest"
}
MAX_PREVIEW_BYTES = 2 * 1024 * 1024


class CodeHighlighter(QSyntaxHighlighter):
    def __init__(self, document, suffix):
        super().__init__(document)
        self.suffix = suffix.lower()
        self.rules = []

        def fmt(color, bold=False, italic=False):
            f = QTextCharFormat()
            f.setForeground(QColor(color))
            if bold:
                f.setFontWeight(QFont.Bold)
            if italic:
                f.setFontItalic(True)
            return f

        self.keyword = fmt("#c792ea", True)
        self.string = fmt("#c3e88d")
        self.comment = fmt("#697386", False, True)
        self.number = fmt("#f78c6c")
        self.function = fmt("#82aaff")
        self.tag = fmt("#f07178", True)
        self.attribute = fmt("#ffcb6b")
        self.rules = [
            (r'"(?:[^"\\]|\\.)*"|\'(?:[^\'\\]|\\.)*\'', self.string),
            (r'\b\d+(?:\.\d+)?\b', self.number),
            (r'\b(?:True|False|None|null|undefined|true|false|nil|this|self|new|return|if|else|elif|for|while|break|continue|class|def|function|const|let|var|import|from|as|export|default|async|await|try|except|finally|throw|catch|switch|case|public|private|static|void|int|string|bool|extends|implements|package|using|namespace|SELECT|FROM|WHERE|INSERT|UPDATE|DELETE|CREATE|TABLE)\b', self.keyword),
            (r'\b[A-Za-z_]\w*(?=\s*\()', self.function),
        ]
        if self.suffix in {".py", ".pyw", ".sh", ".bash", ".rb", ".yaml", ".yml", ".toml", ".ini", ".ps1"}:
            self.rules.append((r'#.*$', self.comment))
        else:
            self.rules.append((r'//.*$', self.comment))
            self.rules.append((r'/\*.*?\*/', self.comment))
        if self.suffix in {".html", ".htm", ".xml", ".svg", ".jsx", ".tsx"}:
            self.rules += [
                (r'</?[A-Za-z][^>]*?>', self.tag),
                (r'\b[A-Za-z-]+(?==)', self.attribute),
            ]

    def highlightBlock(self, text):
        for pattern, style in self.rules:
            import re
            for match in re.finditer(pattern, text):
                self.setFormat(match.start(), match.end() - match.start(), style)


class CodeEditor(QPlainTextEdit):
    def __init__(self):
        super().__init__()
        self.setFont(QFont("Cascadia Code", 10))
        self.setLineWrapMode(QPlainTextEdit.NoWrap)
        self.setTabStopDistance(4 * self.fontMetrics().horizontalAdvance(" "))
        self.setStyleSheet("""
            QPlainTextEdit {
                background: #101722; color: #d6deeb; border: 0;
                selection-background-color: #294766; padding: 12px;
            }
        """)
        self.highlighter = None
        self.current_path = None
        self.is_dirty = False
        self.textChanged.connect(self._mark_dirty)

    def _mark_dirty(self):
        if not self.isReadOnly():
            self.is_dirty = True

    def load_file(self, path):
        path = Path(path)
        if path.stat().st_size > MAX_PREVIEW_BYTES:
            raise ValueError("Файл больше 2 МБ. Для безопасности предпросмотр таких файлов отключён.")
        data = path.read_bytes()
        if b"\0" in data[:8192]:
            raise ValueError("Похоже, это бинарный файл. Его нельзя показать как обычный текст.")
        text = data.decode("utf-8-sig")
        self.blockSignals(True)
        self.setPlainText(text)
        self.blockSignals(False)
        self.current_path = path
        self.is_dirty = False
        self.setReadOnly(True)
        self.highlighter = CodeHighlighter(self.document(), path.suffix)
        self.highlighter.setDocument(self.document())

    def save_file(self):
        if not self.current_path:
            return
        self.current_path.write_text(self.toPlainText(), encoding="utf-8")
        self.is_dirty = False
        self.setReadOnly(True)


class FileMasterWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("FileMaster")
        self.resize(1280, 820)
        self.setMinimumSize(900, 600)
        self.current_dir = Path.home()
        self.current_file = None
        self._build_ui()
        self.navigate(self.current_dir)

    def _build_ui(self):
        self.setStyleSheet("""
            QMainWindow, QWidget { background: #0b111b; color: #dce6f2; }
            QToolBar { background: #111b29; border: 0; spacing: 8px; padding: 9px; }
            QPushButton {
                background: #1b2a3d; color: #e6eef8; border: 1px solid #2a405b;
                border-radius: 7px; padding: 8px 12px;
            }
            QPushButton:hover { background: #263c56; }
            QLineEdit {
                background: #111b29; border: 1px solid #26384e;
                border-radius: 7px; padding: 9px; color: #e6eef8;
            }
            QTreeView {
                background: #0f1723; alternate-background-color: #121d2b;
                border: 0; outline: 0; padding: 5px;
                selection-background-color: #203c5a;
            }
            QHeaderView::section {
                background: #111b29; color: #91a7c0; border: 0;
                padding: 7px; font-weight: 600;
            }
            QTabWidget::pane { border: 1px solid #1c2b3d; }
            QTabBar::tab {
                background: #111b29; color: #91a7c0; padding: 10px 16px;
                border-top-left-radius: 6px; border-top-right-radius: 6px;
            }
            QTabBar::tab:selected { background: #1b2b40; color: #ffffff; }
            QStatusBar { background: #101a28; color: #8ea3bb; }
        """)

        toolbar = QToolBar("Main")
        toolbar.setMovable(False)
        self.addToolBar(toolbar)

        for label, callback in [
            ("← Назад", self.go_up),
            ("↻ Обновить", self.refresh),
            ("＋ Папка", self.new_folder),
            ("＋ Файл", self.new_file),
            ("Переименовать", self.rename_item),
            ("Копировать", self.copy_item),
            ("Переместить", self.move_item),
            ("Удалить", self.delete_item),
        ]:
            btn = QPushButton(label)
            btn.clicked.connect(callback)
            toolbar.addWidget(btn)

        address_row = QWidget()
        address_layout = QHBoxLayout(address_row)
        address_layout.setContentsMargins(12, 8, 12, 8)
        address_layout.addWidget(QLabel("Путь"))
        self.path_input = QLineEdit()
        self.path_input.returnPressed.connect(lambda: self.navigate(self.path_input.text()))
        address_layout.addWidget(self.path_input, 1)
        go_btn = QPushButton("Открыть")
        go_btn.clicked.connect(lambda: self.navigate(self.path_input.text()))
        address_layout.addWidget(go_btn)

        root = QWidget()
        layout = QVBoxLayout(root)
        layout.setContentsMargins(0, 0, 0, 0)
        layout.setSpacing(0)
        layout.addWidget(address_row)

        splitter = QSplitter(Qt.Horizontal)
        splitter.setChildrenCollapsible(False)

        self.model = QFileSystemModel(self)
        self.model.setRootPath(str(Path.home()))
        self.model.setFilter(QDir.AllEntries | QDir.NoDotAndDotDot | QDir.Hidden)

        self.folder_tree = QTreeView()
        self.folder_tree.setModel(self.model)
        self.folder_tree.setRootIndex(self.model.index(str(Path.home())))
        self.folder_tree.setHeaderHidden(True)
        for col in range(1, 4):
            self.folder_tree.hideColumn(col)
        self.folder_tree.doubleClicked.connect(self._tree_open)
        self.folder_tree.setMinimumWidth(210)
        splitter.addWidget(self.folder_tree)

        right = QWidget()
        right_layout = QVBoxLayout(right)
        right_layout.setContentsMargins(0, 0, 0, 0)
        self.tabs = QTabWidget()

        files_page = QWidget()
        files_layout = QVBoxLayout(files_page)
        files_layout.setContentsMargins(0, 0, 0, 0)
        self.file_view = QTreeView()
        self.file_view.setModel(self.model)
        self.file_view.setSortingEnabled(True)
        self.file_view.sortByColumn(0, Qt.AscendingOrder)
        self.file_view.setSelectionBehavior(QAbstractItemView.SelectRows)
        self.file_view.setAlternatingRowColors(True)
        self.file_view.doubleClicked.connect(self._file_open)
        self.file_view.setContextMenuPolicy(Qt.CustomContextMenu)
        self.file_view.customContextMenuRequested.connect(self._context_menu)
        files_layout.addWidget(self.file_view)
        self.tabs.addTab(files_page, "Файлы")

        code_page = QWidget()
        code_layout = QVBoxLayout(code_page)
        code_layout.setContentsMargins(0, 0, 0, 0)
        code_toolbar = QHBoxLayout()
        self.file_label = QLabel("Выберите текстовый файл для просмотра кода")
        self.file_label.setStyleSheet("color:#8ea3bb; padding:8px;")
        code_toolbar.addWidget(self.file_label, 1)
        self.edit_button = QPushButton("Редактировать")
        self.edit_button.clicked.connect(self.toggle_edit)
        self.edit_button.setEnabled(False)
        code_toolbar.addWidget(self.edit_button)
        self.save_button = QPushButton("Сохранить")
        self.save_button.clicked.connect(self.save_code)
        self.save_button.setEnabled(False)
        code_toolbar.addWidget(self.save_button)
        code_layout.addLayout(code_toolbar)
        self.editor = CodeEditor()
        code_layout.addWidget(self.editor)
        self.tabs.addTab(code_page, "Просмотр кода")

        right_layout.addWidget(self.tabs)
        splitter.addWidget(right)
        splitter.setSizes([270, 1010])
        layout.addWidget(splitter, 1)
        self.setCentralWidget(root)

        self.setStatusBar(QStatusBar())
        self.statusBar().showMessage("Готово")

    def navigate(self, path):
        target = Path(os.path.expandvars(os.path.expanduser(str(path)))).resolve()
        if not target.exists() or not target.is_dir():
            QMessageBox.warning(self, "Путь не найден", "Указанная папка не существует или недоступна.")
            return
        self.current_dir = target
        index = self.model.index(str(target))
        self.file_view.setRootIndex(index)
        self.folder_tree.setCurrentIndex(index)
        self.path_input.setText(str(target))
        self.statusBar().showMessage(str(target))

    def _tree_open(self, index):
        path = Path(self.model.filePath(index))
        if path.is_dir():
            self.navigate(path)

    def _file_open(self, index):
        path = Path(self.model.filePath(index))
        if path.is_dir():
            self.navigate(path)
        else:
            self.open_file(path)

    def open_file(self, path):
        try:
            self.editor.load_file(path)
        except (OSError, UnicodeError, ValueError) as exc:
            QMessageBox.information(self, "Не удалось открыть файл", str(exc))
            return
        self.current_file = path
        self.file_label.setText(str(path))
        self.edit_button.setEnabled(True)
        self.save_button.setEnabled(False)
        self.tabs.setCurrentIndex(1)
        self.statusBar().showMessage(f"Открыт файл: {path.name}")

    def toggle_edit(self):
        if not self.editor.current_path:
            return
        editing = self.editor.isReadOnly()
        self.editor.setReadOnly(not editing)
        self.save_button.setEnabled(editing)
        self.edit_button.setText("Только просмотр" if editing else "Редактировать")
        self.statusBar().showMessage("Режим редактирования" if editing else "Режим просмотра")

    def save_code(self):
        if not self.editor.current_path:
            return
        answer = QMessageBox.question(self, "Сохранение", "Сохранить изменения в этом файле?")
        if answer != QMessageBox.Yes:
            return
        try:
            self.editor.save_file()
        except OSError as exc:
            QMessageBox.critical(self, "Ошибка сохранения", str(exc))
            return
        self.save_button.setEnabled(False)
        self.edit_button.setText("Редактировать")
        self.statusBar().showMessage("Файл сохранён")

    def selected_path(self):
        index = self.file_view.currentIndex()
        if index.isValid():
            return Path(self.model.filePath(index))
        return None

    def _context_menu(self, position):
        menu = QMenu(self)
        open_action = menu.addAction("Открыть / посмотреть код")
        rename_action = menu.addAction("Переименовать")
        copy_action = menu.addAction("Копировать")
        move_action = menu.addAction("Переместить")
        delete_action = menu.addAction("Удалить")
        chosen = menu.exec(self.file_view.viewport().mapToGlobal(position))
        if chosen == open_action:
            path = self.selected_path()
            if path and path.is_dir():
                self.navigate(path)
            elif path:
                self.open_file(path)
        elif chosen == rename_action:
            self.rename_item()
        elif chosen == copy_action:
            self.copy_item()
        elif chosen == move_action:
            self.move_item()
        elif chosen == delete_action:
            self.delete_item()

    def go_up(self):
        if self.current_dir.parent != self.current_dir:
            self.navigate(self.current_dir.parent)

    def refresh(self):
        self.model.setRootPath(str(self.current_dir))
        self.file_view.setRootIndex(self.model.index(str(self.current_dir)))
        self.statusBar().showMessage("Список обновлён")

    def new_folder(self):
        name, ok = QInputDialog.getText(self, "Новая папка", "Название папки:")
        if ok and name.strip():
            try:
                (self.current_dir / name.strip()).mkdir()
                self.refresh()
            except OSError as exc:
                QMessageBox.warning(self, "Ошибка", str(exc))

    def new_file(self):
        name, ok = QInputDialog.getText(self, "Новый файл", "Имя файла:")
        if ok and name.strip():
            try:
                path = self.current_dir / name.strip()
                path.touch(exist_ok=False)
                self.refresh()
                self.open_file(path)
            except OSError as exc:
                QMessageBox.warning(self, "Ошибка", str(exc))

    def rename_item(self):
        source = self.selected_path()
        if not source:
            QMessageBox.information(self, "Переименование", "Сначала выбери файл или папку.")
            return
        name, ok = QInputDialog.getText(self, "Переименовать", "Новое имя:", text=source.name)
        if ok and name.strip():
            try:
                source.rename(source.with_name(name.strip()))
                self.refresh()
            except OSError as exc:
                QMessageBox.warning(self, "Ошибка", str(exc))

    def copy_item(self):
        source = self.selected_path()
        if not source:
            QMessageBox.information(self, "Копирование", "Сначала выбери файл или папку.")
            return
        destination = QFileDialog.getExistingDirectory(self, "Куда скопировать?", str(self.current_dir))
        if not destination:
            return
        target = Path(destination) / source.name
        try:
            if source.is_dir():
                shutil.copytree(source, target)
            else:
                shutil.copy2(source, target)
            self.refresh()
        except (OSError, shutil.Error) as exc:
            QMessageBox.warning(self, "Ошибка копирования", str(exc))

    def move_item(self):
        source = self.selected_path()
        if not source:
            QMessageBox.information(self, "Перемещение", "Сначала выбери файл или папку.")
            return
        destination = QFileDialog.getExistingDirectory(self, "Куда переместить?", str(self.current_dir))
        if not destination:
            return
        try:
            shutil.move(str(source), str(Path(destination) / source.name))
            self.refresh()
        except (OSError, shutil.Error) as exc:
            QMessageBox.warning(self, "Ошибка перемещения", str(exc))

    def delete_item(self):
        source = self.selected_path()
        if not source:
            QMessageBox.information(self, "Удаление", "Сначала выбери файл или папку.")
            return
        answer = QMessageBox.question(
            self, "Подтверждение удаления",
            f"Удалить «{source.name}»? Это действие нельзя отменить.",
            QMessageBox.Yes | QMessageBox.No, QMessageBox.No
        )
        if answer != QMessageBox.Yes:
            return
        try:
            if source.is_dir():
                shutil.rmtree(source)
            else:
                source.unlink()
            self.refresh()
        except OSError as exc:
            QMessageBox.warning(self, "Ошибка удаления", str(exc))


def main():
    app = QApplication(sys.argv)
    app.setApplicationName(APP_NAME)
    window = FileMasterWindow()
    window.show()
    sys.exit(app.exec())


if __name__ == "__main__":
    main()
