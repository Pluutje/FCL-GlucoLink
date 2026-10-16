package com.fclglucolink.app.sensor.dexcomg6

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RONDE 236 — achteraf ophalen van gemiste G6-metingen (backfill).
 *
 * Gebaseerd op xDrip (g5model: BackFillTxMessage, BackFillRxMessage,
 * BackFillStream):
 *  - verzoek: opcode 0x50 op de Control-characteristic (zie
 *    [DexcomG6Protocol.buildBackfillRequest]);
 *  - antwoord: opcode 0x51 op Control, 20 bytes (zie [parseStatus]);
 *  - data: notificaties op de Backfill-characteristic. Byte 0 is een
 *    volgnummer (1, 2, 3, ...), byte 1 wordt overgeslagen, de rest is data;
 *  - de samengevoegde data begint met 4 bytes lengte, daarna records van
 *    8 bytes: dexTime (4), glucose (2, laagste 12 bits), staat (1), trend (1).
 *
 * Dit is NIET getest op een echte transmitter. Daarom controleert
 * [selectNew] elk record streng voordat het als meting wordt gebruikt, en
 * logt de driver alle ruwe bytes.
 */
object DexcomG6Backfill {

    /** Antwoord 0x51. Voorbeeld uit xDrip: 51 00 01 01 A1A00200 DDAF0200 82000000 2361 1625. */
    data class StatusRx(
        val status: Int,
        val backfillStatus: Int,
        val identifier: Int,
        val startTime: Int,
        val endTime: Int,
        val bufferLength: Int,
        val bufferCrc: Int
    )

    fun parseStatus(packet: ByteArray): StatusRx? {
        if (packet.size != 20 || packet[0] != 0x51.toByte()) return null
        val crc = DexcomG6Crypto.crc16(packet, 0, packet.size - 2)
        if (crc[0] != packet[packet.size - 2] || crc[1] != packet[packet.size - 1]) return null
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        return StatusRx(
            status = packet[1].toInt() and 0xff,
            backfillStatus = packet[2].toInt() and 0xff,
            identifier = packet[3].toInt() and 0xff,
            startTime = buf.getInt(4),
            endTime = buf.getInt(8),
            bufferLength = buf.getInt(12),
            bufferCrc = buf.getShort(16).toInt() and 0xffff
        )
    }

    data class Record(val dexTime: Int, val glucoseMgdl: Int, val stateRaw: Int, val trendRaw: Int)

    /** Voegt de notificaties van de Backfill-characteristic samen. */
    class Stream {
        private var lastSequence = 0
        private val data = java.io.ByteArrayOutputStream()
        var packetCount = 0
            private set
        var outOfSequence = false
            private set
        var ignoredNonBackfill = false
            private set

        val byteCount: Int get() = data.size()

        fun push(packet: ByteArray) {
            if (packet.size < 3 || ignoredNonBackfill) return
            // Zelfde controle als xDrip: het eerste pakket van echte backfill-data heeft nullen op deze plekken.
            if (lastSequence == 0 && packet.size > 17 &&
                (packet[5] != 0.toByte() || packet[9] != 0.toByte() || packet[17] != 0.toByte())
            ) {
                ignoredNonBackfill = true
                return
            }
            val sequence = packet[0].toInt() and 0xff
            if (sequence != lastSequence + 1) {
                outOfSequence = true
                return
            }
            lastSequence = sequence
            packetCount++
            data.write(packet, 2, packet.size - 2)
        }

        fun bytes(): ByteArray = data.toByteArray()
    }

    /** Leest de records uit de samengevoegde data. */
    fun decode(stream: ByteArray): List<Record> {
        if (stream.size < 4 + 8) return emptyList()
        val buf = ByteBuffer.wrap(stream).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(4)
        val out = ArrayList<Record>()
        while (buf.remaining() >= 8) {
            val dexTime = buf.getInt()
            val glucose = buf.getShort().toInt() and 0xfff
            val state = buf.get().toInt() and 0xff
            val trend = buf.get().toInt()
            out.add(Record(dexTime, glucose, state, trend))
        }
        return out
    }

    const val MIN_GLUCOSE_MGDL = 39
    const val MAX_GLUCOSE_MGDL = 500
    /** Records dichter dan dit bij de live meting horen bij die live meting. */
    const val LIVE_MARGIN_SEC = 120
    const val MIN_SPACING_SEC = 200
    const val MAX_RECORDS = 80

    /**
     * Kiest alleen de records die zeker kloppen:
     *  - nieuwer dan de laatste meting die al verwerkt is ([lastDexTime]);
     *  - ouder dan de live meting ([currentDexTime]) min [LIVE_MARGIN_SEC];
     *  - niet ouder dan [maxAgeSec] gerekend vanaf [currentDexTime];
     *  - glucose tussen [MIN_GLUCOSE_MGDL] en [MAX_GLUCOSE_MGDL];
     *  - staat is een bruikbare meting (Ok / kalibratie nodig / onvoldoende gekalibreerd);
     *  - minstens [MIN_SPACING_SEC] seconden na het vorige gekozen record.
     * De uitkomst is van oud naar nieuw.
     */
    fun selectNew(records: List<Record>, lastDexTime: Int, currentDexTime: Int, maxAgeSec: Int): List<Record> {
        val sorted = records.sortedBy { it.dexTime }
        val out = ArrayList<Record>()
        var previous = lastDexTime
        for (r in sorted) {
            if (r.dexTime <= lastDexTime) continue
            if (r.dexTime > currentDexTime - LIVE_MARGIN_SEC) continue
            if (currentDexTime - r.dexTime > maxAgeSec) continue
            if (r.glucoseMgdl < MIN_GLUCOSE_MGDL || r.glucoseMgdl > MAX_GLUCOSE_MGDL) continue
            val state = DexcomG6CalibrationState.fromRaw(r.stateRaw)
            if (!(state.usableGlucose() || state.insufficientCalibration())) continue
            if (out.isNotEmpty() && r.dexTime - previous < MIN_SPACING_SEC) continue
            out.add(r)
            previous = r.dexTime
            if (out.size >= MAX_RECORDS) break
        }
        return out
    }
}
