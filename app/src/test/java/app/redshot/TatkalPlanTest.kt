package app.redshot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class TatkalPlanTest {
    private val ist = TimeZone.getTimeZone("Asia/Kolkata")
    private fun ms(s: String): Long = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply { timeZone = ist }.parse(s)!!.time
    private fun pax(vararg n: String) = JSONArray().also { a -> n.forEach { a.put(JSONObject().put("name", it).put("age", 30).put("gender", "M")) } }

    @Test fun acOpensAt10TheDayBefore() {
        val now = ms("2026-10-19 09:00:00")
        assertEquals(ms("2026-10-20 10:00:00"), Tatkal.openAt("21/10/2026", "3A", "TQ", now))
        assertEquals(ms("2026-10-20 10:00:00"), Tatkal.openAt("21/10/2026", "2A", "PT", now))
    }
    @Test fun sleeperAndSecondSittingOpenAt11() {
        val now = ms("2026-10-19 09:00:00")
        assertEquals(ms("2026-10-20 11:00:00"), Tatkal.openAt("21/10/2026", "SL", "TQ", now))
        assertEquals(ms("2026-10-20 11:00:00"), Tatkal.openAt("21/10/2026", "2S", "TQ", now))
    }
    @Test fun alreadyOpenOrGeneralMeansNoWaiting() {
        assertEquals(0L, Tatkal.openAt("21/10/2026", "SL", "TQ", ms("2026-10-20 11:00:01")))
        assertEquals(0L, Tatkal.openAt("21/10/2026", "SL", "GN", ms("2026-10-01 00:00:00")))
        assertEquals(0L, Tatkal.openAt("21/10/2026", "SL", "LD", ms("2026-10-01 00:00:00")))
        assertEquals(0L, Tatkal.openAt("bad", "SL", "TQ"))
    }
    @Test fun validationCatchesProblemsBeforeTheyHurt() {
        val now = ms("2026-10-19 09:00:00")
        assertNull(Tatkal.validate("20/10/2026", "15273", "SL", "TQ", pax("A"), now))
        assertNotNull(Tatkal.validate("18/10/2026", "15273", "SL", "TQ", pax("A"), now))          // past
        assertNotNull(Tatkal.validate("20/10/2026", "1527", "SL", "TQ", pax("A"), now))           // train no
        assertNotNull(Tatkal.validate("20/10/2026", "15273", "XX", "TQ", pax("A"), now))          // class
        assertNotNull(Tatkal.validate("20/10/2026", "15273", "SL", "ZZ", pax("A"), now))          // quota
        assertNotNull(Tatkal.validate("20/10/2026", "15273", "SL", "TQ", pax("A", "B", "C", "D", "E"), now))   // tatkal max 4
        assertNull(Tatkal.validate("20/10/2026", "15273", "SL", "GN", pax("A", "B", "C", "D", "E", "F"), now)) // general 6
        assertNotNull(Tatkal.validate("20/10/2026", "15273", "SL", "TQ", JSONArray(), now))
        assertNotNull(Tatkal.validate("30/02/2027", "15273", "SL", "GN", pax("A"), now))          // impossible date
        assertNotNull(Tatkal.validate("20/04/2027", "15273", "SL", "GN", pax("A"), now))          // > 120 days
    }
}
