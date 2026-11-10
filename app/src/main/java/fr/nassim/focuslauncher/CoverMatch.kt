package fr.nassim.focuslauncher

import java.text.Normalizer

/**
 * Choix de la pochette parmi les résultats d'une recherche par titre et artiste.
 *
 * Logique pure (aucune dépendance Android), testée dans CoverMatchTest. Le Bluetooth de l'autoradio
 * ne transmet que du texte, parfois tronqué (« … ») : on compare donc des formes simplifiées
 * (sans accents, sans ponctuation, sans la partie « (feat. …) ») et on préfère ne rien afficher
 * plutôt qu'une pochette qui n'est pas la bonne.
 */
object CoverMatch {

    class Candidate(val title: String, val artist: String, val url: String)

    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_ALNUM = Regex("[^a-z0-9]+")
    private val ARTIST_CUT = Regex("\\s*(,|&|;|/|\\bfeat\\b|\\bft\\b|\\bfeaturing\\b|\\s+x\\s+).*", RegexOption.IGNORE_CASE)

    /** Minuscules, sans accents ni ponctuation, mots séparés par une seule espace. */
    fun norm(s: String): String {
        val flat = MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()
        return NOT_ALNUM.replace(flat, " ").trim()
    }

    /**
     * Titre sans ce qui suit la première parenthèse, le premier crochet ou un tiret isolé
     * (« (feat. …) », « [Remix] », « - Remastered 2011 »).
     */
    fun baseTitle(title: String): String {
        var t = title.trim().trimEnd('…', '.').trim()
        val cut = t.indexOfFirst { it == '(' || it == '[' }
        if (cut > 0) t = t.substring(0, cut)
        val dash = t.indexOf(" - ")
        if (dash > 0) t = t.substring(0, dash)
        return norm(t)
    }

    /** Premier artiste d'une liste (« A, B & C », « A feat. B », « A x B »). */
    fun primaryArtist(artist: String): String {
        val first = ARTIST_CUT.replace(artist.trim(), "")
        return norm(if (first.isBlank()) artist else first)
    }

    /** Texte envoyé au moteur de recherche. Vide si le titre est inexploitable. */
    fun query(title: String, artist: String): String {
        val t = baseTitle(title)
        if (t.isEmpty()) return ""
        return (primaryArtist(artist) + " " + t).trim()
    }

    /** Identifiant d'un morceau pour le cache : même valeur pour deux écritures voisines du même titre. */
    fun key(title: String, artist: String): String = primaryArtist(artist) + "|" + baseTitle(title)

    /** Vrai si [a] commence par [b] sur une frontière de mot (« mamacita remix » commence par « mamacita »). */
    private fun startsWithWord(a: String, b: String): Boolean =
        b.isNotEmpty() && a.startsWith(b) && (a.length == b.length || a[b.length] == ' ')

    private fun containsWord(a: String, b: String): Boolean = b.isNotEmpty() && (" $a ").contains(" $b ")

    /**
     * Adresse de la pochette du résultat qui correspond vraiment, sinon null.
     * Si l'artiste est connu, il doit figurer dans celui du résultat (ou l'inverse). Un résultat de
     * même titre passe avant un résultat dont le titre commence seulement pareil (« Bande organisée »
     * avant « Bande organisée 2 »).
     */
    fun pick(cands: List<Candidate>, title: String, artist: String): String? =
        pick(cands, title, artist, exact = true) ?: pick(cands, title, artist, exact = false)

    private fun pick(cands: List<Candidate>, title: String, artist: String, exact: Boolean): String? {
        val t = baseTitle(title)
        if (t.isEmpty()) return null
        val a = primaryArtist(artist)
        // Titre coupé par le module (« … ») hors parenthèse : la coupure peut tomber au milieu d'un mot.
        val cutShort = title.trim().endsWith("…") && t.length >= 8
        for (c in cands) {
            if (c.url.isBlank()) continue
            val ct = baseTitle(c.title)
            val titleOk = ct == t ||
                (!exact && (startsWithWord(ct, t) || startsWithWord(t, ct) || (cutShort && ct.startsWith(t))))
            if (!titleOk) continue
            if (a.isNotEmpty()) {
                val ca = norm(c.artist)
                if (!(containsWord(ca, a) || containsWord(a, ca))) continue
            }
            return c.url
        }
        return null
    }
}
