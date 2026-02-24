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
 */
public class ConnectedThread extends Thread {

    private static final String TAG = "ConnectedThread";

    private final BluetoothSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;
    private volatile Handler handler;   // volatile so setHandler() is visible across threads
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

        // Continuously read from the InputStream
        while (running) {
            try {
                bytes = inputStream.read(buffer);
                if (bytes > 0) {
                    String message = new String(buffer, 0, bytes);
                    // Send the received message to the UI thread via Handler
                    handler.obtainMessage(Constants.MSG_READ, message).sendToTarget();
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
     */
    public void write(String message) {
        write(message.getBytes());
    }

    public String getDeviceName() {
        return deviceName;
    }

    /** Swap the UI handler so that this thread delivers messages to a new Activity. */
    public void setHandler(Handler newHandler) {
        this.handler = newHandler;
    }

    public boolean isConnected() {
        return socket != null && socket.isConnected() && running;
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
