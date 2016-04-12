package com.android.tools.internal.testing;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

import java.io.BufferedReader;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keep track of device serials that are currently in use.
 *
 * It makes no attempt to keep track of all devices other than for logging purposes.
 *
 *
 * For example, the following sequence of operations would be a typical session while running
 * the connectedIntegrationTests:
 * <pre>
 * - DevicePool: []            No devices are currently in use
 * - reserveDevice([A, B, D])  Pool receives a request for one of A, B or C
 *     - Respond: A            It arbitrarily satisfies the request with device A
 *     - DevicePool: [A]       So device a is now in use
 * - reserveDevice([A.C])      Another request is received for one of A or C
 *     - Respond: C            A is in use, so C is allocated
 *     - DevicePool: [A, C]    Now both A and C are in use
 *
 * - reserveDevice([A,C])
 *     ...Blocked              Blocks until one of A or C are released.
 *
 * - releaseDevice(A)          A is released
 *     - DevicePool: [C]       So now only C is in use
 * ... reserveDevice([A,C])    Unblocked,  which means that the pending request for either
 *     - returns A             A or C can be satisfied with A.
 *</pre>
 *
 */
public class DevicePool {

    private static final DateFormat DATE_FORMAT =
            new SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US);

    /** Guarded by this */
    private Set<String> inUseDevices;
    /** Guarded by this */
    private volatile boolean exclusiveAdbAccess;

    public DevicePool() {
        inUseDevices = new HashSet<>();
        exclusiveAdbAccess = false;
    }

    /**
     * Reserves one device, blocking until one can be allocated.
     *
     * @param serials the list of acceptable device serials
     * @return the allocated serial
     */
    synchronized String getDevice(List<String> serials, String testName) throws InterruptedException {
        String loggedSerialsList = Joiner.on(" or ").join(serials);
        log("Request for %1$s: One of: %2$s", testName, loggedSerialsList);
        while (exclusiveAdbAccess || inUseDevices.containsAll(serials)) {
            this.wait();
        }
        for (String serial: serials) {
            if (!inUseDevices.contains(serial)) {
                inUseDevices.add(serial);
                log("Device allocated: %1$s for request of %2$s by %3$s",
                        serial, loggedSerialsList, testName);
                logStatus();
                return serial;
            }
        }
        throw new RuntimeException("Cannot happen!");
    }


    /**
     * Returns a device to the pool.
     *
     * @param serial the previously allocated serial
     */
    synchronized void returnDevice(String serial, String name) {
        inUseDevices.remove(serial);
        log("Device returned by %1$s: %2$s", serial, name);
        logStatus();
        this.notifyAll();
    }


    /**
     * Reserves exclusive access to all devices.
     */
    synchronized void getAllDevices(String name) throws InterruptedException {
        log("Request for %1$s: Exclusive adb access", name);
        while (exclusiveAdbAccess || !inUseDevices.isEmpty()) {
            this.wait();
        }
        log("Got exclusive adb access for %1$s", name);
        exclusiveAdbAccess = true;
    }

    /**
     * Ends the exclusive access to all devices.
     */
    synchronized void returnAllDevices(String name) {
        exclusiveAdbAccess = false;
        log("Relinquished exclusive adb access by %1$s", name);
        logStatus();
        this.notifyAll();
    }

    private final AtomicBoolean run = new AtomicBoolean(true);

    private ServerSocket mServerSocket;

    public synchronized void start(final int port) throws IOException {
        log("Starting on port %d", port);
        mServerSocket = new ServerSocket(port);
        Thread thread = new Thread(() -> {
            while (run.get()) {
                Socket s;
                try {
                    s = mServerSocket.accept();
                } catch (SocketException ignored) {
                    return;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                DeviceRequestHandler handler = new DeviceRequestHandler(s, DevicePool.this);
                handler.start();
            }
            log("Stopped.");
        });
        thread.setDaemon(true);
        thread.start();
        log("Started.");
    }

    @SuppressWarnings("unused") // Used in integration-test build script.
    public synchronized void stop() throws IOException {
        log("Stopping...");
        run.set(false);
        mServerSocket.close();
        if (!inUseDevices.isEmpty()){
            System.err.format(
                    "Warning: Some devices were never returned: %1$s\n",
                    Joiner.on(", ").join(inUseDevices));
        }

        if (exclusiveAdbAccess) {
            System.err.println("Warning: A test was still holding on to all devices.");
        }

    }

    private static class DeviceRequestHandler extends Thread {

        private final Socket mSocket;
        private final DevicePool mDevicePool;

        DeviceRequestHandler(Socket socket, DevicePool devicePool) {
            mSocket = socket;
            mDevicePool = devicePool;
        }

        @Override
        public void run() {
            BufferedReader in;
            try {
                in = new BufferedReader(new InputStreamReader(mSocket.getInputStream()));
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
            try {
                String commandWithArgs = in.readLine();
                int firstSpace = commandWithArgs.indexOf(' ');
                if (firstSpace == -1) {
                    System.err.println(String.format("Unknown command %s", commandWithArgs));
                    return;
                }
                String command = commandWithArgs.substring(0, commandWithArgs.indexOf(' '));
                String deviceSerials = commandWithArgs.substring(commandWithArgs.indexOf(' '),
                            commandWithArgs.lastIndexOf(' ')).trim();
                String testName =
                            commandWithArgs.substring(commandWithArgs.lastIndexOf(' ')).trim();
                switch (command) {
                    case "request":
                        List<String> requestedSerials =
                                Lists.newArrayList(Splitter.on(',').split(deviceSerials));
                        try (PrintWriter out = new PrintWriter(mSocket.getOutputStream())) {
                            String deviceSerial = mDevicePool.getDevice(requestedSerials, testName);
                            out.println(deviceSerial);
                            out.flush();
                        }
                        break;
                    case "return":
                        mDevicePool.returnDevice(deviceSerials, testName);
                        break;
                    case "requestAll":
                        mDevicePool.getAllDevices(testName);
                        break;
                    case "returnAll":
                        mDevicePool.returnAllDevices(testName);
                        break;
                    default:
                        System.err.println(String.format("Unknown command %s", command));
                        break;
                }


            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            } finally {
                try {
                    in.close();
                    mSocket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void logStatus() {
        log("Devices in use: [%1$s]", Joiner.on(", ").join(inUseDevices));
    }


    private static void log(String log, Object... args) {
        System.out.println("DevicePool " + DATE_FORMAT.format(new Date()) + ": " +
                String.format(log, args));
    }
}
