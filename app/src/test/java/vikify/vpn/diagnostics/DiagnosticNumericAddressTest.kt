package vikify.vpn.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticNumericAddressTest {
    @Test fun parsesIpv4DirectlyWithoutAResolver() {
        assertArrayEquals(byteArrayOf(127, 0, 0, 1), diagnosticNumericAddress("127.0.0.1")!!.address)
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 25), diagnosticNumericAddress("192.0.2.25")!!.address)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), diagnosticNumericAddress("255.255.255.255")!!.address)
    }

    @Test fun parsesCompressedAndExpandedIpv6WithoutAResolver() {
        val loopback = ByteArray(16).apply { this[15] = 1 }
        assertArrayEquals(loopback, diagnosticNumericAddress("::1")!!.address)
        assertArrayEquals(loopback, diagnosticNumericAddress("0:0:0:0:0:0:0:1")!!.address)
        assertArrayEquals(ByteArray(16), diagnosticNumericAddress("::")!!.address)
        assertArrayEquals(diagnosticNumericAddress("2001:db8:0:0:0:0:0:1")!!.address,
            diagnosticNumericAddress("2001:DB8::1")!!.address)
    }

    @Test fun acceptsIpv4MappedAndEmbeddedIpv6() {
        assertArrayEquals(byteArrayOf(192.toByte(), 0, 2, 1), diagnosticNumericAddress("::ffff:192.0.2.1")!!.address)
        assertArrayEquals(diagnosticNumericAddress("2001:db8::c000:201")!!.address,
            diagnosticNumericAddress("2001:db8::192.0.2.1")!!.address)
    }

    @Test fun neverTreatsNamesOrMalformedAddressesAsNumeric() {
        for (host in listOf("localhost", "edge.invalid", "127.0.0.1.evil.invalid", "1.2.3.256",
            "1.2.3", "1.2.3.-1", "", " ", "::1.invalid", "1::2::3", ":::1", "1:2:3",
            "1:2:3:4:5:6:7:8:9", "1:2:3:4:5:6:7:8::", "[::1]", "fe80::1%wlan0",
            "::ffff:999.0.0.1", "1.2.3.4::", " ::1", "::1\n")) {
            assertNull("Only complete numeric literals are accepted", diagnosticNumericAddress(host))
        }
    }
}
