package app.iduanju.core

import app.iduanju.core.hongguo.HGSign
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HGSignGoldenTest {
    @Test
    fun videoModelGorgonGolden() {
        val q = "iid=562075558049240&device_id=562075558045144&ac=wifi&channel=ry_hongguo_offlinestore_04&aid=8662&app_name=novelread&version_code=72133&version_name=7.2.133&device_platform=android&os=android&ssmix=a&device_type=EML-AL00&device_brand=HUAWEI&language=zh&os_api=29&os_version=10&manifest_version_code=72133&resolution=1080*2340&dpi=480&update_version_code=72133&_rticket=1700000000000"
        val body = """{"biz_param":{"detail_page_version":0,"device_level":3,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":true,"use_server_dns_scene":"cold_start_stage","video_platform":1024},"dr_scene":"player","mixed_video_id_map":{"1004":["7683440834066271256"]}}""".toByteArray()
        val h = HGSign.videoModelHeaders(q, body, 1_700_000_000_000L)
        assertEquals("8404401c000033f22a92d6c631cf7022103fad22c9d970977e38", h["X-Gorgon"])
        assertEquals("EEB6CCF4BDA3ABF69F178085C3D1C125", h["X-SS-STUB"])
    }

    @Test
    fun contentKeyUnwrapEmptyRejects() {
        assertTrue(HGSign.contentKey("") == null)
    }
}
