package com.byd.ls.ac

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AcProbeParserTest {

    @Test
    fun windFromLastHexWord() {
        val raw = "Result: Parcel(00000000 00000005 '........' )"
        assertEquals(5, AcProbeParser.parseWind(raw))
    }

    @Test
    fun temperatureFromParcelPrintable() {
        val raw = "Result: Parcel(00000000 00000000 '........2.4.....')"
        assertEquals(24, AcProbeParser.parseTemp(raw))
    }

    @Test
    fun temperatureDecimalFormAlsoParses() {
        assertEquals(24, AcProbeParser.parseTemp("Result: Parcel( '24.0' )"))
    }

    @Test
    fun loAndHiMapToEndpoints() {
        assertEquals(17, AcProbeParser.parseTemp("Result: Parcel( 'Lo' )"))
        assertEquals(33, AcProbeParser.parseTemp("Result: Parcel( 'Hi' )"))
    }

    @Test
    fun invalidValuesReturnNull() {
        assertNull(AcProbeParser.parseWind("Result: Parcel(00000000 00000009 '........' )"))
        assertNull(AcProbeParser.parseTemp("Result: Parcel( 'abc' )"))
        assertNull(AcProbeParser.parseWind(null))
    }
}
