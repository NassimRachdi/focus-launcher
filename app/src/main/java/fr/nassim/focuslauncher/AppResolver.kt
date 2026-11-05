package fr.nassim.focuslauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable

class AppEntry(
    val label: String,
    val intent: Intent,
    val icon: Drawable?,
    val glyph: Int = 0,     // icône vectorielle maison (0 = icône d'origine de l'appli)
    val color: Int = 0      // couleur du badge
)

/**
 * Retrouve les applis déjà installées sur l'autoradio par leur NOM affiché
 * (les noms de paquets MTCB varient d'un firmware à l'autre, les libellés beaucoup moins).
 */
class AppResolver(private val ctx: Context) {

    private class Raw(val label: String, val pkg: String, val cls: String)
    private var raw: List<Raw> = emptyList()

    fun refresh() {
        val pm = ctx.packageManager
        val q = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        raw = pm.queryIntentActivities(q, 0)
            .filter { it.activityInfo.packageName != ctx.packageName }
            .map { Raw(it.loadLabel(pm).toString().trim(), it.activityInfo.packageName, it.activityInfo.name) }
    }

    private fun intentOf(r: Raw) = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(r.pkg, r.cls)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    /** Cherche une appli par libellé (égalité, puis « commence par »), puis par nom de paquet. */
    fun find(labels: Array<String>, packages: Array<String> = emptyArray()): Intent? {
        for (l in labels) raw.firstOrNull { it.label.equals(l, true) }?.let { return intentOf(it) }
        for (l in labels) raw.firstOrNull { it.label.startsWith(l, true) }?.let { return intentOf(it) }
        for (p in packages) raw.firstOrNull { it.pkg == p }?.let { return intentOf(it) }
        return null
    }

    /** Liste pour la page « Applis » : renommée, triée, sans les utilitaires système. */
    fun all(): List<AppEntry> {
        val pm = ctx.packageManager
        val out = ArrayList<AppEntry>()
        val seen = HashSet<String>()
        for (r in raw) {
            if (HIDDEN.any { r.label.equals(it, true) || r.label.startsWith(it, true) }) continue
            val known = KNOWN.firstOrNull { k -> k.match.any { r.label.equals(it, true) || r.label.startsWith(it, true) } }
            val name = known?.name ?: r.label
            if (!seen.add(name.lowercase())) continue // ex. deux « Bluetooth » ou deux « DVD »
            val icon: Drawable? = if (known == null) try { pm.getActivityIcon(intentOf(r)) } catch (_: PackageManager.NameNotFoundException) { null } else null
            out.add(AppEntry(name, intentOf(r), icon, known?.glyph ?: 0, known?.color ?: 0))
        }
        out.sortWith(compareBy({ order(it.label) }, { it.label.lowercase() }))
        return out
    }

    private fun order(label: String): Int {
        val i = KNOWN.indexOfFirst { it.name == label }
        return if (i < 0) 1000 else i
    }

    private class Known(val name: String, val match: Array<String>, val glyph: Int, val color: Int)

    companion object {
        /** Utilitaires masqués de la grille (toujours accessibles via Paramètres → Applications). */
        private val HIDDEN = arrayOf("DisableServ", "mtcxp", "Car Launcher", "CarLauncher", "Lanceur")

        private val KNOWN = arrayOf(
            Known("CarPlay", arrayOf("Autokit", "AutoKit", "ZLink", "CarPlay", "Carlinkit"), R.drawable.ic_carplay, 0xFFE5322D.toInt()),
            Known("Navigation", arrayOf("Navigation"), R.drawable.ic_nav, 0xFF2F8F5B.toInt()),
            Known("Musique", arrayOf("Musique", "Music"), R.drawable.ic_music, 0xFFD94A7A.toInt()),
            Known("Radio", arrayOf("Radio"), R.drawable.ic_radio, 0xFFC98A1E.toInt()),
            Known("Téléphone", arrayOf("Bluetooth"), R.drawable.ic_phone, 0xFF2A7BD4.toInt()),
            Known("Vidéo", arrayOf("Vidéo", "Video"), R.drawable.ic_video, 0xFF5560D6.toInt()),
            Known("DVD", arrayOf("DVD"), R.drawable.ic_disc, 0xFF4A6B8A.toInt()),
            Known("Navigateur", arrayOf("Navigateur", "Browser", "Chrome"), R.drawable.ic_globe, 0xFF1F8F9E.toInt()),
            Known("Explorateur", arrayOf("Explorateur", "File", "Fichiers"), R.drawable.ic_folder, 0xFFB86A2A.toInt()),
            Known("Android Auto", arrayOf("Headunit"), R.drawable.ic_carplay, 0xFF3A7D44.toInt()),
            Known("Entrée AV", arrayOf("AV en", "AV In", "AVIN"), R.drawable.ic_input, 0xFF6B5AA8.toInt()),
            Known("Réglages ampli", arrayOf("Amplifier", "Ampli", "réglages de l'ampli"), R.drawable.ic_eq, 0xFF8A4FA8.toInt()),
            Known("Commandes volant", arrayOf("Key Study", "KeyStudy", "Steering"), R.drawable.ic_wheel, 0xFF5C6470.toInt()),
            Known("Installer APK", arrayOf("ApkInstaller", "APK Installer"), R.drawable.ic_pkg, 0xFF4B8A3C.toInt()),
            Known("Paramètres", arrayOf("Paramètres", "Settings"), R.drawable.ic_gear, 0xFF5C6470.toInt())
        )
    }
}
