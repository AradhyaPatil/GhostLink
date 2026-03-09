package com.wmn.bluetoothmessenger.bluetooth;

import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import com.wmn.bluetoothmessenger.util.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Thread that manages an active Bluetooth socket connection.
 * Continuously reads incoming data and provides a write method for outgoing
 * data.
 * Each connected peer has its own ConnectedThread.
 *
 * <h3>Message framing</h3>
 * All protocol messages are delimited by a newline character ({@code '\n'}).
 * The read loop buffers incoming bytes and splits them on {@code '\n'} so that
 * rapidly sent messages (e.g. LEAVE + SESSION_END) are never concatenated
 * into a single MSG_READ delivery.
 */
public class ConnectedThread extends Thread {

    private static final String TAG = "ConnectedThread";

    /**
     * Delimiter appended to every outgoing message and used to split incoming data.
     */
    public static final String DELIMITER = "\n";

    private final BluetoothSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private volatile Handler handler; // volatile so setHandler() is visible across threads
    private final String deviceName;
    private volatile boolean running = true;

    public ConnectedThread(BluetoothSocket socket, Handler handler, String deviceName) {
        this.socket = socket;
        this.handler = handler;
        this.deviceName = deviceName;

        InputStream tmpIn = null;
        OutputStream tmpOut = null;

        try {
            tmpIn = socket.getInputStream();
            tmpOut = socket.getOutputStream();
        } catch (IOException e) {
            Log.e(TAG, "Error getting streams", e);
        }

        this.inputStream = tmpIn;
        this.outputStream = tmpOut;
    }

    @Override
    public void run() {
        byte[] buffer = new byte[4096];
        int bytes;
        StringBuilder sb = new StringBuilder();

        // Continuously read from the InputStream, buffering until we find a
        // newline delimiter. Each delimited segment is dispatched as a separate
        // MSG_READ message so protocol messages never merge.
        while (running) {
            try {
                bytes = inputStream.read(buffer);
                if (bytes > 0) {
                    sb.append(new String(buffer, 0, bytes));

                    // Process all complete messages (terminated by '\n')
                    int newlineIdx;
                    while ((newlineIdx = sb.indexOf(DELIMITER)) >= 0) {
                        String message = sb.substring(0, newlineIdx);
                        sb.delete(0, newlineIdx + DELIMITER.length());

                        if (!message.isEmpty()) {
                            handler.obtainMessage(Constants.MSG_READ, message).sendToTarget();
                        }
                    }
                }
            } catch (IOException e) {
                if (running) {
                    Log.d(TAG, "Connection lost with " + deviceName);
                    handler.obtainMessage(Constants.MSG_DISCONNECTED, deviceName).sendToTarget();
                }
                break;
            }
        }
    }

    /**
     * Write data to the connected device.
     */
    public void write(byte[] bytes) {
        try {
            outputStream.write(bytes);
            outputStream.flush();
        } catch (IOException e) {
            Log.e(TAG, "Error writing to " + deviceName, e);
            handler.obtainMessage(Constants.MSG_DISCONNECTED, deviceName).sendToTarget();
        }
    }

    /**
     * Write a string message to the connected device.
     * Automatically appends the newline delimiter for proper framing.
     */
    public void write(String message) {
        write((message + DELIMITER).getBytes());
    }

    public String getDeviceName() {
        return deviceName;
    }

    /**
     * Swap the UI handler so that this thread delivers messages to a new Activity.
     */
    public void setHandler(Handler newHandler) {
        this.handler = newHandler;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && running;
    }

    /** Get the Bluetooth MAC address of the remote device. */
    public String getRemoteAddress() {
        return socket != null ? socket.getRemoteDevice().getAddress() : "";
    }

    /**
     * Shut down this connection thread.
     */
    public void cancel() {
        running = false;
        try {
            socket.close();
        } catch (IOException e) {
            Log.e(TAG, "Error closing socket", e);
        }
    }
}
