package app.iduanju.core

import app.iduanju.core.hongguo.Medusa
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MedusaTest {
    @Test
    fun tablesLoadAndMintShape() {
        val t = Medusa.Tables.shared()
        assertEquals(4, t.tabs.size)
        val pb = Medusa.buildPlainPB("https://x/y?a=1&b=2", 1_700_000_000L, ByteArray(16) { it.toByte() })
        val pkt = Medusa.mint(1_700_000_000L, pb)
        // head(4) + 16 + t4(2) + 3 + body(pre) — body = 1 + 8 + (8 + pb) + 2
        assertEquals(4 + 16 + 2 + 3 + 1 + 8 + 8 + pb.size + 2, pkt.size)
        assertTrue(pkt.isNotEmpty())
        // deterministic for fixed inputs
        assertEquals(pkt.toList(), Medusa.mint(1_700_000_000L, pb).toList())
    }
}
