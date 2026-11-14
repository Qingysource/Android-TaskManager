package com.android.taskmanager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.android.taskmanager.data.LinuxControlCode
import com.android.taskmanager.data.LinuxField
import com.android.taskmanager.data.LinuxFieldStatus
import com.android.taskmanager.data.LinuxPacket
import com.android.taskmanager.data.LinuxPacketStatus
import com.android.taskmanager.data.LinuxProcessRecord
import com.android.taskmanager.data.LinuxProtocol
import com.android.taskmanager.data.LinuxRequestType

/**
 * v2 协议编解码的 JVM 单测（不碰管道、不碰 Android，纯字符串 <-> 结构）。
 *
 * 为什么要有这层测试：协议错误在真机上表现为"表格少一行""某个值变成 0"，
 * 很难和采集错误区分开。把编解码单独钉住，排错时就能立刻判断是不是协议层的锅。
 *
 * 运行：`gradlew :app:testDebugUnitTest`（或直接跑本类）。
 */
class LinuxProtocolTest {

    // ── 请求构造 ────────────────────────────────────────────────

    @Test
    fun ping_request_has_no_mask() {
        assertEquals("REQ 7 4 0x0", LinuxProtocol.buildPing(7))
    }

    @Test
    fun control_request_carries_code_then_argument() {
        assertEquals(
            "REQ 9 3 0x0 ${LinuxControlCode.SHUTDOWN}",
            LinuxProtocol.buildControl(9, LinuxControlCode.SHUTDOWN)
        )

        assertEquals(
            "REQ 9 3 0x0 ${LinuxControlCode.KILL_PROCESS} 1234",
            LinuxProtocol.buildControl(9, LinuxControlCode.KILL_PROCESS, "1234")
        )
    }

    @Test
    fun query_request_hex_mask_round_trips() {

        val mask = LinuxField.NAME or LinuxField.PID or LinuxField.CPU_USAGE

        assertEquals(
            "REQ 3 1 0x${java.lang.Long.toHexString(mask)}",
            LinuxProtocol.buildQuery(3, LinuxRequestType.QUERY_PROCESS, mask)
        )
    }

    // ── 包头严格校验（F-01 / F-07）───────────────────────────────

    @Test
    fun header_accepts_wellformed_line() {

        // bit0（名称）+ bit42（隔离状态）：两端都踩到，确认 43 位边界没算错
        val mask = LinuxField.NAME or LinuxField.ISOLATION

        val header = LinuxProtocol.parseHeader(
            "PKG 5 1 1 0x${java.lang.Long.toHexString(mask)} 0 2"
        )

        assertNotNull(header)

        header!!

        assertEquals(5L, header.seq)
        assertTrue(header.isFromSvc)
        assertEquals(LinuxRequestType.QUERY_PROCESS, header.typeCode)
        assertEquals(mask, header.mask)
        assertEquals(LinuxPacketStatus.OK, header.status)
        assertEquals(2, header.count)
    }

    @Test
    fun header_rejects_bad_lines() {

        // seq = 0：序号必须为正，否则无法与请求对上
        assertNull(LinuxProtocol.parseHeader("PKG 0 1 1 0x1 0 1"))

        // isFromSvc = 0：这条通道上客户端不发 PKG
        assertNull(LinuxProtocol.parseHeader("PKG 5 0 1 0x1 0 1"))

        // 未知 typeCode
        assertNull(LinuxProtocol.parseHeader("PKG 5 1 9 0x1 0 1"))

        // 未知状态码
        assertNull(LinuxProtocol.parseHeader("PKG 5 1 1 0x1 9 1"))

        // 掩码越界：43 位以外有 bit
        assertNull(LinuxProtocol.parseHeader("PKG 5 1 1 0x800000000000 0 1"))

        // 字段不够
        assertNull(LinuxProtocol.parseHeader("PKG 5 1 1 0x1"))

        // 不是 PKG
        assertNull(LinuxProtocol.parseHeader("P 5 name=x"))
    }

    @Test
    fun header_status_codes_cover_the_whole_contract() {

        for (status in LinuxPacketStatus.OK..LinuxPacketStatus.PROCESS_EXITED) {
            assertTrue(
                "状态码 $status 应当可解析",
                LinuxProtocol.parseHeader("PKG 5 1 1 0x1 $status 0") != null
            )
        }
    }

    // ── 值编码（F-05）──────────────────────────────────────────────

    @Test
    fun escape_and_unescape_are_inverse() {

        val samples = listOf(
            "Test 1",
            "a=b",
            "100%",
            "line\nbreak",
            "tab\there",
            "说明: 中文 也不该拆行",
            "insert!mark"
        )

        for (sample in samples) {
            val escaped = LinuxProtocol.escapeValue(sample)

            assertFalse("转义后不能留下空白", escaped.any { it == ' ' || it == '\n' || it == '\t' })

            assertEquals(
                "往返必须等值：$sample",
                sample,
                LinuxProtocol.unescapeValue(escaped)
            )
        }
    }

    // ── 进程行解析 ─────────────────────────────────────────────────

    @Test
    fun process_line_splits_values_and_statuses() {

        val record = LinuxProtocol.parseProcessLine(
            "P 42 name=Test1 cpu_usage=!${LinuxFieldStatus.NO_BASELINE} state=1"
        )

        assertNotNull(record)

        record!!

        assertEquals(42, record.pid)

        // 有值的进 fields
        assertEquals("Test1", record.fieldOf(DetailColumnId.NAME))
        assertEquals("1", record.fieldOf(DetailColumnId.STATE))

        // 没值的进 statuses，且**不能**被当作 0
        assertNull(record.fieldOf(DetailColumnId.CPU_USAGE))
        assertEquals(LinuxFieldStatus.NO_BASELINE, record.statusOf(DetailColumnId.CPU_USAGE))

        // displayOf：没值就显示原因，不补 0
        assertFalse(record.displayOf(DetailColumnId.CPU_USAGE).startsWith("0"))
        assertTrue(record.displayOf(DetailColumnId.CPU_USAGE).contains("—"))
    }

    @Test
    fun process_line_decodes_percent_encoded_value() {

        val record = LinuxProtocol.parseProcessLine("P 7 cmdline=/system%2Fbin%2Finit%20--user")

        assertNotNull(record)

        assertEquals("/system/bin/init --user", record!!.fieldOf(DetailColumnId.CMDLINE))
    }

    @Test
    fun process_line_keeps_extension_key_and_drops_invalid_one() {

        // start_ticks 是协议允许的扩展键（防 PID 复用），必须原样保留
        val record = LinuxProtocol.parseProcessLine("P 9 name=x start_ticks=12345")

        assertNotNull(record)

        assertEquals("12345", record!!.fields["start_ticks"])

        // 键名含非法字符：丢弃这一项，但整行仍然可用
        val dirty = LinuxProtocol.parseProcessLine("P 9 name=x bad-key=1")

        assertNotNull(dirty)
        assertNull(dirty!!.fields["bad-key"])
        assertEquals("x", dirty.fieldOf(DetailColumnId.NAME))
    }

    @Test
    fun process_line_rejects_bad_pid() {

        assertNull(LinuxProtocol.parseProcessLine("P 0 name=x"))
        assertNull(LinuxProtocol.parseProcessLine("P abc name=x"))
        assertNull(LinuxProtocol.parseProcessLine("Q 1 name=x"))
    }

    // ── 包完整性（F-06）────────────────────────────────────────────

    @Test
    fun packet_is_not_ok_until_all_records_arrive() {

        val half = LinuxPacket(
            seq = 3,
            isFromSvc = true,
            typeCode = LinuxRequestType.QUERY_PROCESS,
            mask = LinuxField.NAME,
            status = LinuxPacketStatus.OK,
            processes = listOf(
                LinuxProcessRecord(1, mapOf(DetailColumnId.NAME to "Test1"))
            ),
            complete = false
        )

        assertFalse(half.isOk)
        assertEquals("包不完整", half.failureReason)

        val whole = half.copy(complete = true)

        assertTrue(whole.isOk)
        assertNull(whole.failureReason)
    }

    @Test
    fun accepted_is_not_success() {

        // KILL 在"只回执不执行"的实现里回 ACCEPTED，界面不能当成功
        val accepted = LinuxPacket(
            seq = 11,
            isFromSvc = true,
            typeCode = LinuxRequestType.CONTROL,
            mask = 0L,
            status = LinuxPacketStatus.ACCEPTED
        )

        assertTrue(accepted.isAccepted)
        assertFalse(accepted.isOk)
        assertEquals("ACCEPTED", accepted.failureReason)
    }
}
