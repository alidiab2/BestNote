package app.bestnote.engine.export

import androidx.core.content.FileProvider

/** Own subclass so the manifest merger keeps it apart from other modules' FileProviders. */
class ExportFileProvider : FileProvider()
