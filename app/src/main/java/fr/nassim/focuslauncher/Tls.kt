package fr.nassim.focuslauncher

import android.content.Context
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Connexions HTTPS fiables sur Android 5.1.
 *
 * Le magasin de certificats de l'autoradio date de 2016 : il ne connaît pas les autorités racines
 * utilisées aujourd'hui par GitHub et son stockage de fichiers, d'où l'erreur
 * « Trust anchor for certification path not found ». On fait donc confiance aux racines d'Android
 * PLUS quelques racines publiques récentes (res/raw/extra_roots.pem, issues de la liste Mozilla :
 * ISRG X1/X2, USERTrust RSA/ECC, Sectigo R46/E46, DigiCert G2/G3).
 * La vérification reste complète (chaîne et nom d'hôte) : rien n'est désactivé.
 */
object Tls {
    @Volatile private var factory: SSLSocketFactory? = null

    fun socketFactory(ctx: Context): SSLSocketFactory? {
        factory?.let { return it }
        return try {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            // 1. racines d'Android
            val sys = KeyStore.getInstance("AndroidCAStore").apply { load(null, null) }
            for (alias in sys.aliases()) sys.getCertificate(alias)?.let { ks.setCertificateEntry("sys:$alias", it) }
            // 2. racines récentes embarquées
            val cf = CertificateFactory.getInstance("X.509")
            val pem = ctx.applicationContext.resources.openRawResource(R.raw.extra_roots).bufferedReader().use { it.readText() }
            Regex("-----BEGIN CERTIFICATE-----([^-]+)-----END CERTIFICATE-----").findAll(pem).forEachIndexed { i, m ->
                val der = Base64.decode(m.groupValues[1].replace(Regex("\\s"), ""), Base64.DEFAULT)
                ks.setCertificateEntry("extra:$i", cf.generateCertificate(ByteArrayInputStream(der)))
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
            val ssl = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
            ssl.socketFactory.also { factory = it }
        } catch (_: Exception) {
            null // en cas de problème, on garde le comportement d'Android
        }
    }
}
