package app.iduanju.core

import app.iduanju.core.huangguo.HuangguoClient
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/** Live (`-Plive=1`): 黄果 search uses the sites' own search (issue #2 / #3), like iOS. */
class HuangguoSearchLiveTest {
    @Test fun siteSearchFindsIssueKeywords() {
        assumeTrue(System.getProperty("idj.live") == "1")
        val hg = HuangguoClient(Files.createTempDirectory("hgs").toFile(), mutableMapOf())
 for (q in listOf("修仙", "神雕", "宋青书")) {
     val r = hg.search(q)
     println("[live] hg search $q: ${r.size} ${r.groupingBy { it.seriesId.substringBefore(':') }.eachCount()} e.g. ${r.take(3).map { it.title }}")
 }
        assertTrue(hg.search("修仙").size > 10)
    }
}
