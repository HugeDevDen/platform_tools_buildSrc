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
            getters.add(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        devicePool.getAllDevices();
                        try {
                            Thread.sleep(2);
                            Thread.yield();
                        } finally {
                            devicePool.returnAllDevices();
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
                                .getDevice(ImmutableList.of("A", "B", "C"));
                        try {
                            Thread.sleep(2);
                            Thread.yield();
                        } finally {
                            devicePool.returnDevice(device);
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
        assertEquals("A", devicePool.getDevice(ImmutableList.of("A")));
        assertEquals("B", devicePool.getDevice(ImmutableList.of("A", "B")));
        assertEquals("C", devicePool.getDevice(ImmutableList.of("A", "B", "C")));

        devicePool.returnDevice("B");
        devicePool.returnDevice("A");
        devicePool.returnDevice("C");
    }


}
