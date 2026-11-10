package fr.nassim.focuslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Titres tels que le Bluetooth de l'autoradio les donne, face à des résultats de recherche Deezer. */
class CoverMatchTest {

    private fun c(title: String, artist: String, url: String = "https://img/$title") = CoverMatch.Candidate(title, artist, url)

    @Test fun requeteSansFeat() {
        assertEquals("alonzo mamacita", CoverMatch.query("MAMACITA (feat. Soolking & TK)", "Alonzo"))
        assertEquals("bouss felicite", CoverMatch.query("Félicité", "Bouss"))
    }

    @Test fun requeteTitreTronque() {
        assertEquals(
            "triangle des bermudes mopao aye",
            CoverMatch.query("Mopao aye (feat. MC YOSHI, Mauvais Djo & Kokosvoic…", "TRIANGLE DES BERMUDES")
        )
    }

    @Test fun requeteVideSansTitre() {
        assertEquals("", CoverMatch.query("", "Alonzo"))
        assertEquals("", CoverMatch.query("…", ""))
    }

    @Test fun premierArtiste() {
        assertEquals("alonzo", CoverMatch.primaryArtist("Alonzo, Soolking & TK"))
        assertEquals("jul", CoverMatch.primaryArtist("JUL feat. SCH"))
        assertEquals("ninho", CoverMatch.primaryArtist("Ninho x Niska"))
        assertEquals("ac", CoverMatch.primaryArtist("AC/DC"))
    }

    @Test fun memeCleMalgreLEcriture() {
        assertEquals(CoverMatch.key("MAMACITA (feat. Soolking & TK)", "Alonzo"), CoverMatch.key("Mamacita", "ALONZO"))
    }

    @Test fun choisitLeBonResultat() {
        val res = listOf(
            c("Mamacita", "Black Eyed Peas", "https://img/bep"),
            c("MAMACITA (feat. Soolking & TK)", "Alonzo", "https://img/alonzo"),
        )
        assertEquals("https://img/alonzo", CoverMatch.pick(res, "MAMACITA (feat. Soolking & TK)", "Alonzo"))
    }

    @Test fun titreTronqueParLeBluetooth() {
        val res = listOf(c("Mopao aye (feat. MC YOSHI, Mauvais Djo & Kokosvoice)", "TRIANGLE DES BERMUDES", "https://img/tdb"))
        assertEquals("https://img/tdb", CoverMatch.pick(res, "Mopao aye (feat. MC YOSHI, Mauvais Djo & Kokosvoic…", "TRIANGLE DES BERMUDES"))
    }

    @Test fun titreCoupeAuMilieuDUnMot() {
        val res = listOf(c("Une très longue histoire", "Bouss", "https://img/h"))
        // « histoi… » n'est pas un mot entier : le début du titre suffit quand le module a coupé
        assertEquals("https://img/h", CoverMatch.pick(res, "Une très longue histoi…", "Bouss"))
        // sans « … », un début de mot ne suffit pas
        assertNull(CoverMatch.pick(res, "Une très longue histoi", "Bouss"))
    }

    @Test fun accentsEtCasseIgnores() {
        val res = listOf(c("Felicite", "BOUSS", "https://img/f"))
        assertEquals("https://img/f", CoverMatch.pick(res, "Félicité", "Bouss"))
    }

    @Test fun titreExactAvantTitrePlusLong() {
        val res = listOf(c("Bande organisée 2", "Jul", "https://img/2"), c("Bande organisée", "Jul", "https://img/1"))
        assertEquals("https://img/1", CoverMatch.pick(res, "Bande organisée", "Jul, SCH, Naps & Kofs"))
    }

    @Test fun suffixeApresTiretIgnore() {
        assertEquals("queen bohemian rhapsody", CoverMatch.query("Bohemian Rhapsody - Remastered 2011", "Queen"))
        val res = listOf(c("Bohemian Rhapsody (Remastered 2011)", "Queen", "https://img/q"))
        assertEquals("https://img/q", CoverMatch.pick(res, "Bohemian Rhapsody - Remastered 2011", "Queen"))
    }

    @Test fun artisteDifferentRefuse() {
        val res = listOf(c("Mamacita", "Black Eyed Peas"), c("Mamacita", "Pedro Alonso"))
        assertNull(CoverMatch.pick(res, "MAMACITA", "Alonzo"))
    }

    @Test fun titreDifferentRefuse() {
        val res = listOf(c("Mamacitas", "Alonzo"), c("Suis-moi", "Alonzo"))
        assertNull(CoverMatch.pick(res, "MAMACITA", "Alonzo"))
    }

    @Test fun artisteInconnuAccepteLeTitre() {
        val res = listOf(c("Mame", "TRIANGLE DES BERMUDES", "https://img/mame"))
        assertEquals("https://img/mame", CoverMatch.pick(res, "Mame", ""))
    }

    @Test fun resultatSansImageIgnore() {
        val res = listOf(c("Mame", "TRIANGLE DES BERMUDES", ""), c("Mame", "Triangle des Bermudes", "https://img/2"))
        assertEquals("https://img/2", CoverMatch.pick(res, "Mame", "TRIANGLE DES BERMUDES"))
    }

    @Test fun aucunResultat() {
        assertNull(CoverMatch.pick(emptyList(), "Mame", "TRIANGLE DES BERMUDES"))
    }
}
