package com.wmn.bluetoothmessenger.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Handler;
import android.util.Log;

import com.wmn.bluetoothmessenger.util.Constants;

import java.io.IOException;

/**
 * Thread that runs a BluetoothServerSocket to accept incoming connections.
 * The host device runs this thread to allow clients to join the group.
 * For each accepted connection, a new ConnectedThread is created.
 */
public class AcceptThread extends Thread {

    private static final String TAG = "AcceptThread";

    private final BluetoothServerSocket serverSocket;
    private final Handler handler;
    private final BluetoothService bluetoothService;
    private volatile boolean running = true;

    @SuppressWarnings("MissingPermission")
    public AcceptThread(BluetoothAdapter adapter, Handler handler, BluetoothService bluetoothService) {
        this.handler = handler;
        this.bluetoothService = bluetoothService;

        BluetoothServerSocket tmp = null;
        try {
            tmp = adapter.listenUsingRfcommWithServiceRecord(
                    Constants.BT_SERVICE_NAME, Constants.BT_UUID);
        } catch (IOException e) {
            Log.e(TAG, "Failed to create server socket", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth permission missing", e);
        }
        this.serverSocket = tmp;
    }

    @Override
    public void run() {
        Log.d(TAG, "Accept thread started, waiting for connections...");

        while (running) {
            try {
                if (serverSocket == null) {
                    Log.e(TAG, "Server socket is null, stopping accept thread");
                    break;
                }
                // This call blocks until a connection is accepted or the socket is closed
                BluetoothSocket socket = serverSocket.accept();

                if (socket != null) {
                    Log.d(TAG, "Connection accepted from: " + getDeviceName(socket));
                    // Hand off to BluetoothService for auth + registration
                    bluetoothService.onConnectionAccepted(socket);
                }
            } catch (IOException e) {
                if (running) {
                    Log.e(TAG, "Accept failed", e);
                }
                break;
            }
        }
    }

    @SuppressWarnings("MissingPermission")
    private String getDeviceName(BluetoothSocket socket) {
        try {
            return socket.getRemoteDevice().getName();
        } catch (SecurityException e) {
            return socket.getRemoteDevice().getAddress();
        }
    }

    /**
     * Cancel the accept thread and close the server socket.
     */
    public void cancel() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error closing server socket", e);
        }
    }
}
