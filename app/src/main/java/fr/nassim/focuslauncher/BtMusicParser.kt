package fr.nassim.focuslauncher

/**
 * Décodage des lignes que le service Bluetooth MTC (android.microntek.mtcser) écrit dans le journal
 * système pour le module Bluetooth de type 6 (cfg_bt=6), étiquette « linlin » :
 *
 *   BT 6 MI<titre>|<artiste>|<durée ms>|<n° de piste>|<nombre de pistes>
 *   BT 6 MB      lecture en cours
 *   BT 6 MA      pause / arrêt
 *
 * Relevé sur l'autoradio le 06/11/2026 (voir docs/DIAGNOSTIC-ADB.md). Le séparateur, noté « | »
 * ci-dessus, est en réalité le caractère U+FFFD : le module envoie un octet invalide en UTF-8,
 * que le service remplace avant d'écrire.
 * Le service ne rediffuse ces informations ni par broadcast ni par session média ; le journal est
 * donc la seule source. Aucune dépendance Android ici : testé sur la JVM (BtMusicParserTest).
 */
object BtMusicParser {

    const val PREFIX = "BT 6 "
    const val SEP = '�'

    /** Le module coupe le titre à 50 octets et colle alors l'artiste à la suite, sans séparateur. */
    private const val TITLE_MAX_BYTES = 50

    sealed class Event
    data class Track(
        val title: String,
        val artist: String,
        val durationMs: Long,
        val index: Int,
        val total: Int
    ) : Event()
    data class State(val playing: Boolean) : Event()

    /** Renvoie l'événement porté par une ligne du journal, ou null si la ligne ne concerne pas la musique. */
    fun parse(line: String): Event? {
        val i = line.indexOf(PREFIX)
        if (i < 0) return null
        val p = line.substring(i + PREFIX.length).trim()
        return when {
            p == "MB" -> State(true)
            p == "MA" -> State(false)
            p.startsWith("MI") -> track(p.substring(2))
            else -> null
        }
    }

    private fun track(s: String): Track? {
        val f = s.split(SEP)
        val n = f.size
        if (n < 3) return null
        // Les champs numériques sont lus depuis la fin : un titre coupé au milieu d'un caractère
        // accentué peut faire apparaître un séparateur de trop au début.
        val numbers = if (n >= 5) n - 3 else 2
        var title = f[0].trim()
        val artist = (if (n >= 5) f[n - 4] else f[1]).trim()
        if (artist.isNotEmpty() && title.length > artist.length && title.endsWith(artist)) {
            val head = title.substring(0, title.length - artist.length)
            if (head.toByteArray(Charsets.UTF_8).size >= TITLE_MAX_BYTES - 3) title = head.trimEnd() + "…"
        }
        return Track(
            title = title,
            artist = artist,
            durationMs = f[numbers].trim().toLongOrNull() ?: 0L,
            index = f.getOrNull(numbers + 1)?.trim()?.toIntOrNull() ?: 0,
            total = f.getOrNull(numbers + 2)?.trim()?.toIntOrNull() ?: 0
        )
    }
}
