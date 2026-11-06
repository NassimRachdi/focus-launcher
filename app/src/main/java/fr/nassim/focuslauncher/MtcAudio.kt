package fr.nassim.focuslauncher

import android.content.Context
import android.media.AudioManager

/**
 * Commutation de la source audio sur la plateforme MTC (Microntek).
 *
 * Sur cet autoradio, le module Bluetooth n'est pas celui d'Android : le son de l'iPhone arrive
 * sur une entrée du processeur audio, que le pilote noyau MTC sélectionne quand il reçoit
 * « av_channel_enter=gsm_bt ». Sources de cette commande (rien n'est deviné) :
 *  - pilote MTC rétro-documenté (amper128/mtc_drivers, car.c) : jetons « av_channel_enter »,
 *    « av_channel_exit », « av_channel », canaux « gsm_bt », « sys », « fm »… ;
 *  - applis MTC publiques (ex. MTCRadioAutoMuter) : sur MTCB, ces clés passent par
 *    AudioManager.setParameters.
 * Lecture seule pour « av_channel » ; aucune commande n'est envoyée au véhicule.
 */
object MtcAudio {

    private fun am(ctx: Context) = ctx.applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    /** Bascule la sortie de l'autoradio sur l'entrée Bluetooth (musique et appels du téléphone). 0 = accepté. */
    fun enterBluetooth(ctx: Context): Int = set(ctx, "av_channel_enter=gsm_bt")

    /** Rend la sortie à Android (lecteurs installés sur l'autoradio). 0 = accepté. */
    fun enterSystem(ctx: Context): Int = set(ctx, "av_channel_enter=sys")

    /**
     * Envoie une paire clé=valeur au HAL audio. Renvoie le code d'AudioFlinger : 0 = accepté,
     * -1 = refusé (permission MODIFY_AUDIO_SETTINGS absente), autre = erreur.
     * AudioManager.setParameters jette ce code ; on passe donc par AudioSystem (API cachée,
     * accessible par réflexion sur Android 5.1) et on se rabat sur AudioManager si besoin.
     */
    fun set(ctx: Context, kv: String): Int {
        try {
            val m = Class.forName("android.media.AudioSystem").getMethod("setParameters", String::class.java)
            return (m.invoke(null, kv) as? Int) ?: UNKNOWN
        } catch (_: Throwable) { }
        return try { am(ctx).setParameters(kv); UNKNOWN } catch (_: Exception) { ERROR }
    }

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkCallingOrSelfPermission("android.permission.MODIFY_AUDIO_SETTINGS") == android.content.pm.PackageManager.PERMISSION_GRANTED

    const val UNKNOWN = 1000  // envoyé via AudioManager, résultat inconnu
    const val ERROR = 1001

    /** Canal actuel tel que le rapporte la plateforme ; "" si illisible. Deux formes de clé selon le firmware. */
    fun channel(ctx: Context): String {
        for (k in arrayOf("av_channel", "av_channel=")) {
            val v = try { am(ctx).getParameters(k) ?: "" } catch (_: Exception) { "" }
            val s = v.substringAfter("=", v).trim()
            if (s.isNotEmpty()) return s
        }
        return ""
    }
}
