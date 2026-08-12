package com.damianhoward.visitoranalytics.device

import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.equalTo
import org.junit.jupiter.api.Test

class UserAgentClassifierTest {
    private val classifier = UserAgentClassifier()

    @Test
    fun `desktop chrome on windows`() {
        val device =
            classifier.classify(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36",
            )
        assertThat(device, equalTo(Device("Chrome", "Windows", Device.Kind.DESKTOP)))
    }

    @Test
    fun `edge is not chrome, despite saying chrome`() {
        val device =
            classifier.classify(
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.87",
            )
        assertThat(device.browser, equalTo("Edge"))
    }

    @Test
    fun `safari is not chrome, and macos is recognised`() {
        val device =
            classifier.classify(
                "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                    "Version/17.5 Safari/605.1.15",
            )
        assertThat(device, equalTo(Device("Safari", "macOS", Device.Kind.DESKTOP)))
    }

    @Test
    fun `iphone safari is mobile ios`() {
        val device =
            classifier.classify(
                "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) " +
                    "Version/17.5 Mobile/15E148 Safari/604.1",
            )
        assertThat(device, equalTo(Device("Safari", "iOS", Device.Kind.MOBILE)))
    }

    @Test
    fun `android chrome is mobile`() {
        val device =
            classifier.classify(
                "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/126.0.0.0 Mobile Safari/537.36",
            )
        assertThat(device, equalTo(Device("Chrome", "Android", Device.Kind.MOBILE)))
    }

    @Test
    fun `firefox on linux`() {
        val device = classifier.classify("Mozilla/5.0 (X11; Linux x86_64; rv:127.0) Gecko/20100101 Firefox/127.0")
        assertThat(device, equalTo(Device("Firefox", "Linux", Device.Kind.DESKTOP)))
    }

    @Test
    fun `bots are bots whatever else they claim`() {
        assertThat(
            classifier.classify("Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)").kind,
            equalTo(Device.Kind.BOT),
        )
        assertThat(classifier.classify("curl/8.5.0").kind, equalTo(Device.Kind.BOT))
    }

    @Test
    fun `blank is unknown`() {
        assertThat(classifier.classify(""), equalTo(Device("unknown", "unknown", Device.Kind.UNKNOWN)))
    }
}
