package com.android.tools.internal.testing;

import static org.junit.Assert.assertEquals;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

import org.junit.Test;

import java.util.List;

public class DevicePoolTest {

    @Test(timeout = 500)
    public void checkDevicePoolSanity() throws Exception {
        final DevicePool devicePool = new DevicePool();

        checkState(devicePool);

        List<Thread> getters = Lists.newArrayList();

        for (int i = 0; i < 20; i++) {
            final String testName = "test " + i;
            getters.add(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        devicePool.getAllDevices("All devices " + testName);
                        try {
                            Thread.sleep(2);
                            Thread.yield();
                        } finally {
                            devicePool.returnAllDevices("All devices" + testName);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }));
            getters.add(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        String device = devicePool
                                .getDevice(ImmutableList.of("A", "B", "C"), "Single device " + testName);
                        try {
                            Thread.sleep(2);
                            Thread.yield();
                        } finally {
                            devicePool.returnDevice(device, "Single device " + testName);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
            }));
        }


        for (Thread getter : getters) {
            getter.start();
        }

        for (Thread getter : getters) {
            getter.join();
        }

        checkState(devicePool);
    }

    private static void checkState(DevicePool devicePool) throws InterruptedException {
        assertEquals("A", devicePool.getDevice(ImmutableList.of("A"), "Checkstate A"));
        assertEquals("B", devicePool.getDevice(ImmutableList.of("A", "B"), "Checkstate AB"));
        assertEquals("C", devicePool.getDevice(ImmutableList.of("A", "B", "C"), "Checkstate ABC"));

        devicePool.returnDevice("B", "Checkstate AB");
        devicePool.returnDevice("A", "Checkstate A");
        devicePool.returnDevice("C", "Checkstate ABC");
    }


}
