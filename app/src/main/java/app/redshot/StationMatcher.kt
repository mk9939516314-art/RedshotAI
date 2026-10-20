package app.redshot

import kotlin.math.abs
import kotlin.math.max

/** Turns what the owner SAYS ("betiya", "jammu", "sugauli") into IRCTC station codes (BTH, JAT, SGL). Pure, unit-tested. */
class StationMatcher(lines: List<String>) {
    data class Hit(val code: String, val name: String, val score: Double)

    private val rows: List<Pair<String, String>> = lines.mapNotNull {
        val i = it.lastIndexOf('|'); if (i <= 0) null else it.substring(0, i).trim().uppercase() to it.substring(i + 1).trim().uppercase()
    }
    private val codes: Set<String> = rows.map { it.second }.toSet()
    private val names: Map<String, String> = rows.associate { it.second to it.first }

    companion object {
        private val STOP = setOf("JN", "JUNCTION", "STATION", "RLY", "RS", "CITY")
        // spoken / old / alternative names that the plain list cannot match
        val ALIAS = mapOf(
            "SUGAULI" to "SGL", "DELHI" to "NDLS", "NEW DELHI" to "NDLS", "OLD DELHI" to "DLI", "BOMBAY" to "CSMT", "MUMBAI" to "CSMT",
            "MUMBAI CENTRAL" to "BCT", "CALCUTTA" to "KOAA", "KOLKATA" to "KOAA", "MADRAS" to "MAS", "CHENNAI" to "MAS", "BANGALORE" to "SBC",
            "BENGALURU" to "SBC", "JAMMU" to "JAT", "JAMMU TAWI" to "JAT", "PATNA" to "PNBE", "GORAKHPUR" to "GKP", "LUCKNOW" to "LKO",
            "VARANASI" to "BSB", "BANARAS" to "BSB", "BENARES" to "BSB", "HOWRAH" to "HWH", "AHMEDABAD" to "ADI", "JAIPUR" to "JP",
            "KANPUR" to "CNB", "AGRA" to "AGC", "ALLAHABAD" to "PRYJ", "PRAYAGRAJ" to "PRYJ", "AMRITSAR" to "ASR", "CHANDIGARH" to "CDG",
            "BETIYA" to "BTH", "BETTIAH" to "BTH", "BETIA" to "BTH", "MUZAFFARPUR" to "MFP", "DARBHANGA" to "DBG", "RAXAUL" to "RXL",
            "HAJIPUR" to "HJP", "RANCHI" to "RNC", "SIWAN" to "SV", "CHHAPRA" to "CPR", "CHAPRA" to "CPR", "BARAUNI" to "BJU",
            "SAMASTIPUR" to "SPJ", "KATIHAR" to "KIR", "NARKATIAGANJ" to "NKE", "BAGAHA" to "BUG", "SECUNDERABAD" to "SC", "HYDERABAD" to "HYB",
            "PUNE" to "PUNE", "NAGPUR" to "NGP", "BHOPAL" to "BPL", "INDORE" to "INDB", "GAYA" to "GAYA", "DHANBAD" to "DHN", "ASANSOL" to "ASN",
            "GUWAHATI" to "GHY", "DEHRADUN" to "DDN", "HARIDWAR" to "HW", "PATHANKOT" to "PTK", "LUDHIANA" to "LDH", "KATRA" to "SVDK",
            "SHRI MATA VAISHNO DEVI KATRA" to "SVDK", "UDHAMPUR" to "UHP", "KOTA" to "KOTA", "JODHPUR" to "JU", "UDAIPUR" to "UDZ",
        )
        fun clean(s: String): String = s.uppercase().replace(Regex("[^A-Z0-9 ]"), " ").split(' ').filter { it.isNotEmpty() && it !in STOP }.joinToString(" ")
        fun skel(s: String): String = clean(s).replace(" ", "").replace(Regex("[AEIOUY]"), "").replace("H", "").replace(Regex("(.)\\1+"), "$1")
        fun lev(a: String, b: String): Int {
            var d = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val p = d.copyOf(); d[0] = i
                for (j in 1..b.length) d[j] = minOf(p[j] + 1, d[j - 1] + 1, p[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            return d[b.length]
        }
        fun ratio(a: String, b: String): Double = if (a.isEmpty() || b.isEmpty()) 0.0 else 1.0 - lev(a, b).toDouble() / max(a.length, b.length)
    }

    fun nameOf(code: String): String = names[code.uppercase()] ?: code

    /** best matches first; the first one is the answer when its score is high (>= 85), otherwise ask the owner. */
    fun find(query: String, limit: Int = 4): List<Hit> {
        val qs = query.uppercase().trim()
        if (qs.isEmpty()) return emptyList()
        val qc = clean(qs)
        ALIAS[qc]?.let { return listOf(Hit(it, nameOf(it), 100.0)) }
        if (Regex("[A-Z]{1,5}").matches(qs) && qs in codes) return listOf(Hit(qs, nameOf(qs), 100.0))
        val qk = skel(qs)
        val out = ArrayList<Hit>()
        for ((n, c) in rows) {
            val nc = clean(n)
            val sc = when {
                nc == qc -> 99.0
                nc.startsWith(qc) || (qc.startsWith(nc) && nc.length > 3) -> 88.0 - abs(nc.length - qc.length)
                nc.contains(qc) -> 78.0
                else -> max(ratio(nc.replace(" ", ""), qc.replace(" ", "")) * 80, if (qk.length >= 2) ratio(skel(n), qk) * 72 else 0.0)
            }
            if (sc >= 40) out.add(Hit(c, n, Math.round(sc * 10) / 10.0))
        }
        return out.sortedWith(compareByDescending<Hit> { it.score }.thenBy { it.name.length }).take(limit)
    }
}
