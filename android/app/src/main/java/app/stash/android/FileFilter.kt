// Path: android/app/src/main/java/app/stash/android/FileFilter.kt
package app.stash.android

/** Which files phone backup takes, by type and by excluded extension. Plain Kotlin, no Android classes. */
object FileFilter {
    enum class Type(val label: String) {
        PHOTOS("Photos"), VIDEOS("Videos"), AUDIO("Audio"), DOCUMENTS("Documents"),
        ARCHIVES("Archives"), APPS("App files (APK)"), OTHER("Other files"),
    }

    private val BY_EXT: Map<String, Type> = buildMap {
        fun add(t: Type, list: String) = list.split(' ').forEach { put(it, t) }
        add(Type.PHOTOS, "jpg jpeg png gif webp heic heif bmp tif tiff dng raw")
        add(Type.VIDEOS, "mp4 mkv mov avi webm 3gp m4v")
        add(Type.AUDIO, "mp3 m4a aac wav flac ogg opus amr")
        add(Type.DOCUMENTS, "pdf doc docx xls xlsx ppt pptx odt ods odp txt md rtf csv epub json xml html")
        add(Type.ARCHIVES, "zip rar 7z tar gz tgz bz2 xz")
        add(Type.APPS, "apk apks xapk apkm")
    }

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun typeOf(name: String, mime: String): Type = BY_EXT[extension(name)] ?: when {
        mime.startsWith("image/") -> Type.PHOTOS
        mime.startsWith("video/") -> Type.VIDEOS
        mime.startsWith("audio/") -> Type.AUDIO
        mime.startsWith("text/") -> Type.DOCUMENTS
        else -> Type.OTHER
    }

    /** "*.tmp, .LOG  bak" -> [tmp, log, bak] */
    fun parseExtensions(text: String): Set<String> =
        text.lowercase().split(Regex("[,;\\s]+")).map { it.trimStart('*', '.') }.filter { it.isNotEmpty() }.toSet()

    /** [offTypes] = names of the [Type]s switched off. */
    fun accepts(name: String, mime: String, offTypes: Set<String>, excluded: Set<String>): Boolean {
        if (typeOf(name, mime).name in offTypes) return false
        // matched against the end of the name, so "tar.gz" works too
        val lower = name.lowercase()
        return excluded.none { lower.endsWith(".$it") }
    }
}
