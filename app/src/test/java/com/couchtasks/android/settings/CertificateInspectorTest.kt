package com.couchtasks.android.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException

class CertificateInspectorTest {
    @Test
    fun caCertificateSha256Fingerprint_returnsColonSeparatedSha256Fingerprint() {
        assertEquals(
            "5C:C3:F5:0F:5D:95:7B:F1:A3:D3:1C:2F:D6:91:05:1D:72:C2:70:F2:FE:45:E2:47:A6:23:90:D9:12:B0:9D:04",
            CertificateInspector.caCertificateSha256Fingerprint(SAMPLE_CA_CERTIFICATE.byteInputStream()),
        )
    }

    @Test(expected = CertificateException::class)
    fun caCertificateSha256Fingerprint_rejectsNonCertificates() {
        CertificateInspector.caCertificateSha256Fingerprint(ByteArrayInputStream("not a certificate".toByteArray()))
    }

    companion object {
        private val SAMPLE_CA_CERTIFICATE = """
-----BEGIN CERTIFICATE-----
MIIFNTCCAx2gAwIBAgIUeKPfAPrZ/0bvlRv2tQkkwis8SzcwDQYJKoZIhvcNAQEL
BQAwIjEgMB4GA1UEAwwXY291Y2gtdGFza3Mgc3luYyBzZXJ2ZXIwHhcNMjYwNDI4
MTM0NjUzWhcNMzEwNDI3MTM0NjUzWjAiMSAwHgYDVQQDDBdjb3VjaC10YXNrcyBz
eW5jIHNlcnZlcjCCAiIwDQYJKoZIhvcNAQEBBQADggIPADCCAgoCggIBANNyczaq
0GGDgRABBhBHKTK/RHMhvKxvwfezr8myIIMRYtfItcJHdBPYtyepX02hHHP7uu00
gkLe8uTI7G+VZ0X4jY1LheXVkNYvXi8DqAGyYvCArtbdj0gKg4h5Vu4ouJhfzBTs
hRpL7oSEWSr2dThpNVY4Vu0njjwOJjK154cJ3Qo04HwmGaaApfizprQ1ziYqLa2E
jgeeoTj9wAtI8ToWCeZUPwL52M++KaAVV8HiwezJX/1uWhrNqWnes3iRFZvRRZ/A
rhxvqx4VWy4NCCsNYC5Zvq2y02YyPe0a8M+Ov1N9oG3QBsO8ldhTLBCrNC6u/vKt
/MO5wZDdycYM+QIlVtGh3ntXk/ycwqJ/I2kXekdJZw8SMWq43s0vH9JqDVnPIt67
62/i5gWGIK0hk2iKy9sq9A3EtaTLZOaMdV3Ab5JLr9cTxKtObMjFb1wfeyqyC53d
Qw/HFG6ncJkUZ3+vbCbFUfLXiUNasKyMCO0JFNsjv5QXFXyp9oM9JeIozsIaf7hW
RjqlnDukJv4YlqKJjL4VT01P/fsScMbz0BdJFuLPfaTxMCddhiUC3Njc8NEIC+Jl
rYHfJXRQxiLXF2P9KzWLHg/HBtJQSC1iO/fjHxhh1qhi0b+X4xRa/SdWOR0Lpiqp
xwL4TktzRoSAjF/YvPZ6MYalSp01btE6aTCLAgMBAAGjYzBhMB0GA1UdDgQWBBSd
gQJ8W+FZkl5kMKbxD4nT1LbKCDAfBgNVHSMEGDAWgBSdgQJ8W+FZkl5kMKbxD4nT
1LbKCDAPBgNVHRMBAf8EBTADAQH/MA4GA1UdDwEB/wQEAwIBBjANBgkqhkiG9w0B
AQsFAAOCAgEAh1jSrrxEO5cngt0bWDUa9rA+vwgwKOxyzKHKLQrRcToV18YUoh4n
a4VW7IJos6KK/P4m1dW+mI4PWoPAfJIiRpQKu5yMIMsRZd1nEgzAnDdrdax3i2CD
zqSDCLCD27bqaQA+5OSM75Oyw5NfLOLzs4cErGthqubliD7tu0hF2YaidgjlNvEy
wsONBQFNiCCa0IBBO4QMGFpURGr7FxL7qkmEF4HaBonJEg0w/dTuZnyd7pUlKIra
oDAlMLpOYKscTlSQW6I4ECHhpy5kIDRr2rBL/ZUlWhVSWR1Nx688kv88AeeiY4Z2
AJbcCRjSzs4l7bA9V3O9S95gEcYO9x4Pg0kiXCdC0bfTeYoj2aGTfEqWViSpHhq7
51btJPVfVroPhAcfIIqfxNMYnrJP80c527JeD5l7MAZuUlsCUB/xC0WktLeu9xQS
aGRlonGv3P7pWxOAstEzbYt0FBoj8lKa0/CmTDZj+wFqIAPZSGWjchv7u9OakCrd
7UH81HdX7RcGuTpQw7IiucQnyBeuqOFT00i9nn9bg49HLiXW3RXgrdWS355IpYbm
r1dMH2PBXtcJAitfZPmFxKEfzv+fN26nLp0P/OqpEXdlQ2JNY+JG5TOI7D0Sxo1s
5swCuIDl06Xq3d0aYeIl6jMpkSaQdO54jN5nLXw4+VEnAllwRrBeBvw=
-----END CERTIFICATE-----
""".trimIndent()
    }
}
