package app.redshot

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class StationMatcherTest {
    private val m: StationMatcher by lazy {
        val f = listOf("src/main/assets/tatkal/stations.txt", "app/src/main/assets/tatkal/stations.txt").map(::File).first { it.exists() }
        StationMatcher(f.readLines())
    }
    private fun code(q: String) = m.find(q).firstOrNull()?.code

    @Test fun spokenNamesBecomeIrctcCodes() {
        val want = mapOf("betiya" to "BTH", "bettiah" to "BTH", "sugauli" to "SGL", "jammu tawi" to "JAT", "jammu" to "JAT", "gorakhpur" to "GKP",
            "new delhi" to "NDLS", "delhi" to "NDLS", "patna" to "PNBE", "howrah" to "HWH", "lucknow" to "LKO", "raxaul" to "RXL",
            "muzafarpur" to "MFP", "bhagalpur" to "BGP", "sitamadhi" to "SMI", "kanpur central" to "CNB", "vijayawada" to "BZA",
            "secunderabad" to "SC", "tirupati" to "TPTY", "sealdah" to "SDAH", "nautanwa" to "NTV", "NDLS" to "NDLS", "bth" to "BTH")
        for ((q, c) in want) assertEquals("query '$q'", c, code(q))
    }

    @Test fun codesComeBackWithAName() {
        assertEquals("BETTIAH", m.nameOf("BTH"))
        assertEquals("JAMMU TAWI", m.nameOf("JAT"))
    }

    @Test fun unknownTextGivesNoConfidentAnswer() {
        val h = m.find("zzzzqqq")
        assertTrue(h.isEmpty() || h.first().score < 60)
    }

    @Test fun listHasThousandsOfStations() {
        assertTrue(m.find("lucknow").isNotEmpty())
        assertTrue(File(listOf("src/main/assets/tatkal/stations.txt", "app/src/main/assets/tatkal/stations.txt").first { File(it).exists() }).readLines().size > 8000)
    }
}
