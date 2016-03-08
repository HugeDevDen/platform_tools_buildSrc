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
        inUseDevices = new HashSet<String>();
        exclusiveAdbAccess = false;
    }

    /**
     * Reserves one device, blocking until one can be allocated.
     *
     * @param serials the list of acceptable device serials
     * @return the allocated serial
     */
    synchronized String getDevice(List<String> serials) throws InterruptedException {
        String loggedSerialsList = Joiner.on(" or ").join(serials);
        log("Request: One of: %1$s", loggedSerialsList);
        while (exclusiveAdbAccess || inUseDevices.containsAll(serials)) {
            this.wait();
        }
        for (String serial: serials) {
            if (!inUseDevices.contains(serial)) {
                inUseDevices.add(serial);
                log("Device allocated: %1$s for request of %2$s", serial, loggedSerialsList);
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
    synchronized void returnDevice(String serial) {
        inUseDevices.remove(serial);
        log("Device returned: %1$s", serial);
        logStatus();
        this.notifyAll();
    }


    /**
     * Reserves exclusive access to all devices.
     */
    synchronized void getAllDevices() throws InterruptedException {
        log("Request: Exclusive adb access");
        while (exclusiveAdbAccess || !inUseDevices.isEmpty()) {
            this.wait();
        }
        log("Got exclusive adb access");
        exclusiveAdbAccess = true;
    }

    /**
     * Ends the exclusive access to all devices.
     */
    synchronized void returnAllDevices() {
        exclusiveAdbAccess = false;
        log("Relinquished exclusive adb access");
        logStatus();
        this.notifyAll();
    }

    private final AtomicBoolean run = new AtomicBoolean(true);

    private ServerSocket mServerSocket;

    public synchronized void start(final int port) throws IOException {
        log("Starting on port %d", port);
        mServerSocket = new ServerSocket(port);
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
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
            }
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
                String command = in.readLine();
                if (command.startsWith("request ")) {
                    String deviceSerials = command.substring("request ".length());
                    List<String> requestedSerials =
                            Lists.newArrayList(Splitter.on(' ').split(deviceSerials));
                    PrintWriter out = new PrintWriter(mSocket.getOutputStream());
                    try {
                        String deviceSerial = mDevicePool.getDevice(requestedSerials);
                        out.println(deviceSerial);
                        out.flush();
                    } finally {
                        out.close();
                    }
                } else if (command.startsWith("return ")) {
                    String deviceSerial = command.substring("return ".length());
                    mDevicePool.returnDevice(deviceSerial);
                } else if (command.equals("requestAll")) {
                    mDevicePool.getAllDevices();
                } else if (command.equals("returnAll")) {
                    mDevicePool.returnAllDevices();
                } else {
                    System.err.println(String.format("Unknown command %s", command));
                }
                mSocket.close();

            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    public void logStatus() {
        log("Devices in use: [%1$s]", Joiner.on(", ").join(inUseDevices));
    }


    private static void log(String log, Object... args) {
        System.out.println("DevicePool " + DATE_FORMAT.format(new Date()) + ": " +
                String.format(log, args));
    }
}
