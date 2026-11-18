# FileMaster

A Windows desktop file manager with a built-in source-code viewer/editor.

## Features

- Browse folders and files using a familiar tree and file list.
- Open text and source files with syntax highlighting.
- View code without editing; explicitly switch to edit mode to change a file.
- Create files and folders, rename, copy, move, and delete items.
- Navigate by entering a folder path.
- Large and binary files are protected from accidental text preview.

## Run on Windows 10/11

1. Install Python 3.10 or newer.
2. Download or clone this repository.
3. Double-click `run.bat`, or run:

```powershell
py -m pip install -r requirements.txt
py app.py
```

## Notes

FileMaster works with folders accessible to the Windows account running it. Be careful when deleting or editing files. The app asks for confirmation before saving edits and deleting items.
