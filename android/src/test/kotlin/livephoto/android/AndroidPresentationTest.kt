package livephoto.android

import livephoto.core.*
import kotlin.test.*

class AndroidPresentationTest {
    @Test fun structuredContextIdentifiesInputsAndMergeStepsWithSelectedNames() {
        val steps = mapOf("inspect-image" to ("image" to "解析图片"), "inspect-video" to ("video" to "解析视频"),
            "prepare-image-metadata" to ("image" to "合并准备"), "write-output" to ("output" to "合并写入"),
            "verify-output" to ("output" to "回读校验"), "publish-output" to ("output" to "提交"))
        for ((step, pair) in steps) {
            val (role, label) = pair
            val text = formatCoreFailure(CoreError(IssueCode("TEST_FAILURE"), Stage.Parse, "specific cause",
                details = mapOf("livephoto.operation" to Value.Text("create"), "livephoto.step" to Value.Text(step),
                    "livephoto.inputRole" to Value.Text(role))), "cover.jpg", "clip.mp4")
            assertTrue(text.contains(label))
            assertTrue(text.contains("specific cause"))
            if (role == "image") { assertTrue(text.contains("图片输入（cover.jpg）")); assertFalse(text.contains("clip.mp4")) }
            if (role == "video") { assertTrue(text.contains("视频输入（clip.mp4）")); assertFalse(text.contains("cover.jpg")) }
            if (role == "output") assertTrue(text.contains("合并结果 / 输出"))
            assertFalse(text.contains("livephoto.step"))
        }
    }

    @Test fun missingContextDoesNotGuessRoleFromMessageOrSourceId() {
        val text = formatCoreFailure(CoreError(IssueCode("PARSE_FAILED"), Stage.Parse, "video file rejected",
            Location(source = SourceId("android-video:anything"))))
        assertTrue(text.contains("未知（错误未提供定位"))
        assertFalse(text.contains("关联对象：视频输入"))
    }

    @Test fun androidReadAndSaveFailuresIdentifyTheirActualStep() {
        val read = formatAndroidFailure(AndroidOperationFailure("read-video", "video", SecurityException("Read grant expired")), "cover.jpg", "clip.mp4")
        assertTrue(read.contains("视频输入（clip.mp4）"))
        assertTrue(read.contains("读取所选视频"))
        assertTrue(read.contains("SecurityException：Read grant expired"))
        assertTrue(read.contains("重新选择"))
        val saved = formatAndroidFailure(AndroidOperationFailure("save-output", "output", IllegalStateException("Storage unavailable")))
        assertTrue(saved.contains("保存生成结果到系统相册"))
        assertTrue(saved.contains("Storage unavailable"))
        assertFalse(saved.contains("AndroidOperationFailure"))
    }

    @Test fun fileSizeDistinguishesUnknownZeroAndBinaryUnits() {
        assertTrue(formatMediaSize(null).startsWith("未知"))
        assertTrue(formatMediaSize(-1).startsWith("未知"))
        assertEquals("0 B", formatMediaSize(0))
        assertEquals("1023 B", formatMediaSize(1023))
        assertEquals("1.00 KiB（1024 字节）", formatMediaSize(1024))
        assertEquals("1.00 MiB（1048576 字节）", formatMediaSize(1048576))
        assertTrue(formatMediaSize(Long.MAX_VALUE).contains(Long.MAX_VALUE.toString()))
    }

    @Test fun coreFailurePreservesActualReasonStageLocationAndDetails() {
        val text = formatCoreFailure(CoreError(IssueCode("UNSUPPORTED_CONTAINER"), Stage.Parse,
            "Multiple sample descriptions are not implemented",
            Location(source = SourceId("selected-video"), range = ByteRange(12u, 30u), track = TrackId("2")),
            mapOf("codec" to Value.Text("hvc1"), "fields" to Value.ArrayValue(listOf(Value.Number("2"), Value.BooleanValue(true))))))
        listOf("UNSUPPORTED_CONTAINER", "Parse", "Multiple sample descriptions are not implemented", "selected-video", "12 + 30", "hvc1", "[2, true]").forEach {
            assertTrue(text.contains(it), "Missing actual failure information: $it")
        }
        assertTrue(text.contains("不会自动转码"))
    }

    @Test fun failureRenderingBoundsUntrustedDiagnosticText() {
        val text = formatCoreFailure(CoreError(IssueCode("PARSE_FAILED"), Stage.Parse, "x".repeat(20000),
            details = mapOf("reason" to Value.Text("y".repeat(20000)))))
        assertTrue(text.length < 6000)
        assertTrue(text.contains("PARSE_FAILED"))
    }

    @Test fun androidFailuresRetainCauseAndActionWithoutStackTrace() {
        val permission = formatAndroidFailure(SecurityException("Read grant expired"))
        assertTrue(permission.contains("Read grant expired"))
        assertTrue(permission.contains("重新选择"))
        assertFalse(permission.contains("at livephoto"))
        val budget = formatAndroidFailure(IllegalArgumentException("Input byte budget exceeded"))
        assertTrue(budget.contains("Input byte budget exceeded"))
        assertTrue(budget.contains("32 MiB"))
    }
}
