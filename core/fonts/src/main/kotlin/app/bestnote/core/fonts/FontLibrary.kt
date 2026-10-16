package app.bestnote.core.fonts

import java.io.File

/**
 * The fonts stored in the app's own files: downloaded OFL families (`gf/<dir>.ttf` + `.OFL.txt`) and the
 * user's own (`my/<n>.ttf` + `.meta`). Every file is validated ([FontValidator]) before it is kept, and
 * written atomically. Pure JVM so it is tested without a device.
 */
class FontLibrary(private val root: File) {
    private val gf get() = File(root, "gf")
    private val mine get() = File(root, "my")

    /** Downloaded and own fonts present on disk (bundled ones are not listed here). */
    fun list(): List<AppFont> {
        val out = ArrayList<AppFont>()
        FontCatalogue.all.forEach { c ->
            val f = File(gf, c.dir + ".ttf")
            if (f.isFile) out += AppFont(c.id, c.family, FontOrigin.Downloaded, c.scripts, f)
        }
        mine.listFiles { f -> f.extension == "meta" }.orEmpty().sortedBy { it.nameWithoutExtension.toIntOrNull() ?: Int.MAX_VALUE }.forEach { meta ->
            val n = meta.nameWithoutExtension
            val file = File(mine, "$n.ttf")
            if (!file.isFile) return@forEach
            val lines = runCatching { meta.readLines() }.getOrDefault(emptyList())
            val name = lines.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Font $n"
            val scripts = lines.getOrNull(1).orEmpty().split(',').mapNotNull { s -> FontScript.entries.firstOrNull { it.name == s } }.toSet()
            out += AppFont(MY_PREFIX + n, name, FontOrigin.Mine, scripts, file)
        }
        return out
    }

    /** Keeps the catalogue font's [bytes] after checking they are a font covering the scripts the catalogue promises. */
    fun installDownloaded(font: CatalogueFont, bytes: ByteArray, license: String?): AppFont {
        val info = FontValidator.inspect(bytes)
        if (!info.scripts.containsAll(font.scripts)) throw FontRejected(FontRejected.Kind.NoLetters, "${font.family} lacks ${font.scripts - info.scripts}")
        gf.mkdirs()
        val target = File(gf, font.dir + ".ttf")
        atomicWrite(target, bytes)
        if (license != null) atomicWrite(File(gf, font.dir + ".OFL.txt"), license.toByteArray())
        return AppFont(font.id, font.family, FontOrigin.Downloaded, font.scripts, target)
    }

    /** Adds the user's own font. [fileName] (e.g. "MyHand.ttf") names it when the file has no family name. */
    fun importMine(fileName: String, bytes: ByteArray): AppFont {
        val info = FontValidator.inspect(bytes)
        mine.mkdirs()
        val n = (mine.listFiles { f -> f.extension == "meta" }.orEmpty().mapNotNull { it.nameWithoutExtension.toIntOrNull() }.maxOrNull() ?: 0) + 1
        val name = (info.familyName ?: fileName.substringBeforeLast('.')).replace(Regex("[\\r\\n,]"), " ").trim().take(60).ifEmpty { "Font $n" }
        val file = File(mine, "$n.ttf")
        atomicWrite(file, bytes)
        atomicWrite(File(mine, "$n.meta"), (name + "\n" + info.scripts.joinToString(",") { it.name }).toByteArray())
        return AppFont(MY_PREFIX + n, name, FontOrigin.Mine, info.scripts, file)
    }

    fun delete(id: String): Boolean = when {
        id.startsWith(CatalogueFont.ID_PREFIX) -> FontCatalogue.byId(id)?.let {
            File(gf, it.dir + ".OFL.txt").delete()
            File(gf, it.dir + ".ttf").delete()
        } ?: false
        id.startsWith(MY_PREFIX) -> id.removePrefix(MY_PREFIX).takeIf { s -> s.all(Char::isDigit) && s.isNotEmpty() }?.let { n ->
            File(mine, "$n.meta").delete()
            File(mine, "$n.ttf").delete()
        } ?: false
        else -> false
    }

    fun rename(id: String, name: String): Boolean {
        if (!id.startsWith(MY_PREFIX)) return false
        val n = id.removePrefix(MY_PREFIX).takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) } ?: return false
        val meta = File(mine, "$n.meta").takeIf { it.isFile } ?: return false
        val lines = meta.readLines()
        atomicWrite(meta, (name.replace(Regex("[\\r\\n,]"), " ").trim().take(60).ifEmpty { return false } + "\n" + lines.getOrNull(1).orEmpty()).toByteArray())
        return true
    }

    /** The OFL text saved with a downloaded font, if any. */
    fun licenseText(id: String): String? = FontCatalogue.byId(id)?.let { File(gf, it.dir + ".OFL.txt").takeIf(File::isFile)?.readText() }

    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".part")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.delete()
            check(tmp.renameTo(target)) { "could not write ${target.name}" }
        }
    }

    companion object {
        const val MY_PREFIX = "my:"
    }
}
