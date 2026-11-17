package com.rkacast

import com.rkacast.server.ByteRange
import com.rkacast.server.RangeParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RangeParserTest {
    @Test fun noHeaderIsFullContent() = assertEquals(ByteRange(0, 999, false), RangeParser.parse(null, 1000))
    @Test fun openEnded() = assertEquals(ByteRange(100, 999, true), RangeParser.parse("bytes=100-", 1000))
    @Test fun closed() = assertEquals(ByteRange(0, 1, true), RangeParser.parse("bytes=0-1", 1000))
    @Test fun endClamped() = assertEquals(ByteRange(500, 999, true), RangeParser.parse("bytes=500-5000", 1000))
    @Test fun suffix() = assertEquals(ByteRange(900, 999, true), RangeParser.parse("bytes=-100", 1000))
    @Test fun suffixLargerThanFile() = assertEquals(ByteRange(0, 999, true), RangeParser.parse("bytes=-5000", 1000))
    @Test fun multiRangeUsesFirst() = assertEquals(ByteRange(0, 9, true), RangeParser.parse("bytes=0-9,20-29", 1000))
    @Test fun startPastEnd() = assertNull(RangeParser.parse("bytes=1000-", 1000))
    @Test fun reversed() = assertNull(RangeParser.parse("bytes=50-10", 1000))
    @Test fun garbage() = assertNull(RangeParser.parse("bytes=abc-", 1000))
    @Test fun nonBytesUnitIgnored() = assertEquals(ByteRange(0, 999, false), RangeParser.parse("items=0-5", 1000))
    @Test fun lengthIsInclusive() = assertEquals(2L, ByteRange(0, 1, true).length)
}
