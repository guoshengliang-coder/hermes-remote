package com.hermes.client.data.network

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * HG-140: failure records carry the active network's transports so "the VPN rule said DIRECT"
 * becomes checkable against what the failing traffic actually sat on. The reader is a process
 * singleton installed by HermesApp; tests install their own and restore the default after.
 */
class NetworkTransportsTest {
    @After fun restoreDefault() = NetworkTransports.install { "unknown" }

    @Test fun before_any_install_the_reader_reports_unknown_rather_than_crashing() {
        NetworkTransports.install { "unknown" }
        assertEquals("unknown", NetworkTransports.current())
    }

    @Test fun an_installed_reader_answers_with_the_transports_it_was_given() {
        NetworkTransports.install { "cellular+vpn" }
        assertEquals("cellular+vpn", NetworkTransports.current())
    }

    @Test fun a_later_install_replaces_the_earlier_reader() {
        NetworkTransports.install { "wifi" }
        NetworkTransports.install { "none" }
        assertEquals("none", NetworkTransports.current())
    }
}
