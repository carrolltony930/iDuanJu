package app.iduanju.core

import app.iduanju.core.hongguo.HGPlaybackSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Playback (video_model) signing must equal the iOS implementation byte for byte.
 * Vectors 1–2: hongguo-open-player 3f687c5 (also asserted by iOS HGPlayerSigningTests).
 * Vectors 3–4: produced by iOS `HGPlayerSign.playbackHeaders` with fixed randoms (2026-11-10).
 */
class HGPlaybackSignTest {
    @Test fun openPlayerVector1() {
        val h = HGPlaybackSign.headers("aid=1967&device_id=dev_aaaaaaaa&iid=111", ByteArray(0), 1700000000u, 659662259u, 67305985u)
        assertEquals("8oF304qEDbfv2tmuZJhSIR1pFbZa9D3QxvEEiVNrm54Av877F40mKNw0TvKKIWEIod2TkiLHixpM/c7R/aH6SN4vNA1rmBvt6FoGwDuVmfhMF9iiybRqoTUi2RbP8uE5ALD4Ab7uj/YzTRotQ6Pcg5DHng6zlrLi8umMY06+ej6UDAUZ5SJhbxRwtrH6xnwiVN2oHb48o0UuVo+5NzLAfo7m0RTVrlv1tqexZboMHKNDJ39b8HlSahjZyJ5/ETs3dVZeoQ3MPF7vQE6qDjGksCd8", h["x-argus"])
        assertEquals("AQIDBKM7Y7bWnOIUXmTybD6/MpGbuge4sBwUJsyHVivhtMsH", h["x-ladon"])
        assertFalse(h.containsKey("x-gorgon")); assertFalse(h.containsKey("x-medusa")); assertFalse(h.containsKey("x-ss-stub"))
    }

    @Test fun openPlayerVector2() {
        val body = "{\"video_id\":\"7683440834066271256\"}".toByteArray()
        val h = HGPlaybackSign.headers("aid=8662&device_id=991110292605353&iid=991110292609449&version_name=7.3.9.32", body, 1794360000u, 4294967295u, 67305985u)
        assertEquals("8oHu8LnZm0OtJJIaSr1427D+yeH70h5aZTVjoSW7MfFi/t2rocw2LWZ7ULBmtMrW3pmk5mJ4qzKxqG1G36abIZv8YAjaDMsYcpgPT2Y5M6Z3ZDszEpebzSF/Bwt0CSPFWeOMbJbsJKVT1ew4K2RC0nVzw/h38QsvS1Q3qk9KEcbdCx42RFAmfdl/C8eSY+LOxNtugUeG+UU5w5CNdVkmE6JRPpkM+3mn24orHxoamSIwvoQIoxtJ91jjOfI+s6PxKger/MSDf5XzMWTL/+tmlc2Q5WUEH4Rgp5Q+pqHD8m1zsA==", h["x-argus"])
        assertEquals("AQIDBN3Xttm3qfFgkTVv7tBrb9bF8APHUjBLe2ja98bNrRn+", h["x-ladon"])
    }

    @Test fun iosVector3() {
        val q = "iid=1111&device_id=2222&aid=8662&version_name=7.3.9.32&ts=1762700000&_rticket=1762700000123"
        val body = """{"biz_param":{"device_level":3,"need_all_video_definition":true,"video_id_type":0,"video_platform":3},"video_id":"v0abc"}""".toByteArray()
        val h = HGPlaybackSign.headers(q, body, 1762700000u, 0x12345678u, 0x9abcdef0u)
        assertEquals("8oEzzYYQyj8eTZ27kE36t4leAIHdoIk7gpm/pA5oXyaDQIEIyyH2UdXap+RvnuMQUBUR3wdflyTce9sPJhDDezJD82xc63On1/WIGBvAedlboYmcjvNuaXoWFNOHktgIkQ3Il5U3nQ1hP9iTL743NOofH/DkzXteDAgWEw/yRgz38vJgeZLdUa9cZnTKursqkYdmH+ZUpvE9iE96fgizqvOX4ow2d5RMOfhCDIo8EnD8zacm3xu9FOCwTDw9h30XKkGDWrKuGlvMpInPohitAF3q", h["x-argus"])
        assertEquals("8N68mpMnQ3aC0RGexeYA4lbEYJiLpkUW+b5gVdj/MPLV6hZ3", h["x-ladon"])
        assertEquals("1762700000", h["x-khronos"])
        assertEquals("1762700000000", h["x-ss-req-ticket"])
        assertEquals("075C4D56057EA51A7735D35FDBD3C3A6", h["x-ss-stub"])
    }

    @Test fun iosVector4EmptyQueryAndBody() {
        val h = HGPlaybackSign.headers("", ByteArray(0), 7u, 1u, 2u)
        assertEquals("8oHAH26Sbp9WBxIQWA8MN9eox+aFcBalk7RNICCaGHil3RQ08294iTPr033RHVuDHBX9rbBi+DMiU1/MxukCPzNdoH6OX2w1ARio6OHeKAt/Kzhms4BdoYSnMEhV6qIHthXYu5wyXq40ZzQQk9omfnJHEm610d8KcOS7+jsktHD9R2REtCKj4DmjTlcbElgbdN1sBKe74fA6QZHaqpdhi5haxG+/FHq6pej3uB/qqwpw5Q==", h["x-argus"])
        assertEquals("AgAAAEg0rwWlQCv3i8lyBvNhV2gzKYTApVya12oaV9rgJ0gz", h["x-ladon"])
        assertEquals("7000", h["x-ss-req-ticket"])
    }
}
