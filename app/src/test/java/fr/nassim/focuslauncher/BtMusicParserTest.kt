package fr.nassim.focuslauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Lignes réelles relevées sur l'autoradio le 06/11/2026 (logcat, étiquette « linlin »),
 * iPhone en Bluetooth sur Apple Music. � est le séparateur tel qu'il apparaît dans le journal.
 */
class BtMusicParserTest {

    private fun track(line: String) = BtMusicParser.parse(line) as BtMusicParser.Track

    @Test fun titreSimple() {
        val t = track("BT 6 MIMAMACITA (feat. Soolking & TK)�Alonzo�144843�1643�1653")
        assertEquals("MAMACITA (feat. Soolking & TK)", t.title)
        assertEquals("Alonzo", t.artist)
        assertEquals(144843L, t.durationMs)
        assertEquals(1643, t.index)
        assertEquals(1653, t.total)
    }

    @Test fun titreAccentue() {
        val t = track("BT 6 MIFélicité�Bouss�184506�0�0")
        assertEquals("Félicité", t.title)
        assertEquals("Bouss", t.artist)
        assertEquals(184506L, t.durationMs)
    }

    /** Titre de plus de 50 octets : le module le coupe et colle l'artiste derrière, sans séparateur. */
    @Test fun titreTronqueParLeModule() {
        val t = track(
            "BT 6 MIMopao aye (feat. MC YOSHI, Mauvais Djo & KokosvoicTRIANGLE DES BERMUDES" +
                "�TRIANGLE DES BERMUDES�198520�14�14"
        )
        assertEquals("Mopao aye (feat. MC YOSHI, Mauvais Djo & Kokosvoic…", t.title)
        assertEquals("TRIANGLE DES BERMUDES", t.artist)
        assertEquals(198520L, t.durationMs)
        assertEquals(14, t.index)
    }

    /** Un titre court qui se termine par le nom de l'artiste n'est pas une coupure. */
    @Test fun titreCourtFinissantParLArtiste() {
        val t = track("BT 6 MIBest of Alonzo�Alonzo�1000�1�2")
        assertEquals("Best of Alonzo", t.title)
    }

    @Test fun titreCourt() {
        val t = track("BT 6 MIMame�TRIANGLE DES BERMUDES�196533�5�120")
        assertEquals("Mame", t.title)
        assertEquals("TRIANGLE DES BERMUDES", t.artist)
        assertEquals(120, t.total)
    }

    @Test fun lectureEtPause() {
        assertEquals(BtMusicParser.State(true), BtMusicParser.parse("BT 6 MB"))
        assertEquals(BtMusicParser.State(false), BtMusicParser.parse("BT 6 MA"))
    }

    @Test fun ligneAvecHorodatage() {
        val e = BtMusicParser.parse("11-06 14:19:56.387 V/linlin  (  878): BT 6 MB")
        assertEquals(BtMusicParser.State(true), e)
    }

    @Test fun lignesSansRapportAvecLaMusique() {
        assertNull(BtMusicParser.parse("BT 6 "))
        assertNull(BtMusicParser.parse("BT 6 S33"))
        assertNull(BtMusicParser.parse("BT 6 PS0201"))
        assertNull(BtMusicParser.parse("--------- beginning of main"))
        assertNull(BtMusicParser.parse("BT 6 MI"))
        assertNull(BtMusicParser.parse(""))
    }
}
