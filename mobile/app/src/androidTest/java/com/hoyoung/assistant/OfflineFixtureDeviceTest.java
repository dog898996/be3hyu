package com.hoyoung.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Test-only preflight on a fresh AVD; never changes a grant or fabricates network state. */
@RunWith(AndroidJUnit4.class)
public final class OfflineFixtureDeviceTest {
    @Test public void actualConnectivityManagerReportsNoActiveOrRemainingNetworks() throws Exception {
        assertTrue("This fixture must never run on a physical phone", Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish"));
        assertEquals(35, Build.VERSION.SDK_INT);
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        assertNotNull("The Android network service must be available", connectivity);
        long deadline = SystemClock.elapsedRealtime() + 30_000;
        int consecutiveEmpty = 0;
        do {
            if (connectivity.getActiveNetwork() == null && connectivity.getAllNetworks().length == 0) {
                if (++consecutiveEmpty == 5) break;
            } else consecutiveEmpty = 0;
            SystemClock.sleep(200);
        } while (SystemClock.elapsedRealtime() < deadline);
        JSONArray remaining = new JSONArray();
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            LinkProperties links = connectivity.getLinkProperties(network);
            JSONObject observation = new JSONObject().put("networkId", network.toString())
                    .put("interface", links == null ? JSONObject.NULL : links.getInterfaceName());
            JSONArray transports = new JSONArray();
            if (capabilities != null) for (int transport : new int[]{NetworkCapabilities.TRANSPORT_CELLULAR,
                    NetworkCapabilities.TRANSPORT_WIFI, NetworkCapabilities.TRANSPORT_BLUETOOTH,
                    NetworkCapabilities.TRANSPORT_ETHERNET, NetworkCapabilities.TRANSPORT_VPN,
                    NetworkCapabilities.TRANSPORT_WIFI_AWARE, NetworkCapabilities.TRANSPORT_LOWPAN,
                    NetworkCapabilities.TRANSPORT_USB, NetworkCapabilities.TRANSPORT_THREAD,
                    NetworkCapabilities.TRANSPORT_SATELLITE}) {
                if (capabilities.hasTransport(transport)) transports.put(transport);
            }
            remaining.put(observation.put("transportTypes", transports));
        }
        assertEquals("No real framework network may remain: " + remaining, 5, consecutiveEmpty);
        assertNull("The actual default network must remain absent", connectivity.getActiveNetwork());
        assertEquals("Even non-default Android networks must be removed", 0, connectivity.getAllNetworks().length);
        // Structured instrumentation output survives runner stdout filtering.
        Bundle proof = new Bundle();
        proof.putString("stream", "HOYOUNG_FIXTURE_FRAMEWORK_OFFLINE actual-activeNetwork=null; actual-getAllNetworks=0; five-consecutive-observations; no-production-bypass\n");
        InstrumentationRegistry.getInstrumentation().sendStatus(0, proof);
    }
}
