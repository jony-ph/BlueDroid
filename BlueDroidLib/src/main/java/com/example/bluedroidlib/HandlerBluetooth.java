package com.example.bluedroidlib;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class HandlerBluetooth {

    private static Activity currentActivity;

    private ConnectThread mmConnectThread;
    private ConnectedThread mmConnectedThread;

    private BluetoothAdapter bluetoothAdapter;
    private BluetoothSocket mmSocket;

    private byte[] mmBuffer; // mmBuffer store for the stream
    public int numBytes; // bytes returned from read()

    static final UUID mUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String TAG = "MY_APP_DEBUG_TAG";

    private static final int STATE_NONE = 0;
    private static final int STATE_LISTEN = 1;
    private static final int STATE_CONNECTING = 2;
    private static final int STATE_CONNECTED = 3;

    private int state;
    private int newState;

    // Obtener activity actual de Unity
    public static  void receiveUnityActivity(Activity UnityActivity) {
        currentActivity = UnityActivity;
    }

    // Comprobar que BT sea compatible y que esté habilitado
    public void requirements() {
        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        if (bluetoothAdapter == null) {
            Toast.makeText(currentActivity, " Bluetooth no compatible", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            // Force Bluetooth activation
            try{
                bluetoothAdapter.enable();
                bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            } catch (RuntimeException e) {
                Toast.makeText(currentActivity, "Permiso denegado", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        Toast.makeText(currentActivity, "Requisitos cumplidos", Toast.LENGTH_SHORT).show();

    }

    // Obtener HC-05 y realizar proceso de conexión por Bluetooth
    public void connect() {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        BluetoothDevice hc05 = null;

        if (pairedDevices.size() <= 0) {
            return;
        }

        // There are paired devices. Get the name and address of each paired device.
        for (BluetoothDevice device : pairedDevices) {
            String deviceName = device.getName();
            String deviceHardwareAddress = device.getAddress(); // MAC address

            if ( deviceHardwareAddress.equals("98:D3:61:F6:23:62") ) {
                hc05 = device;
                Toast.makeText(currentActivity, deviceName + " encontrado", Toast.LENGTH_SHORT).show();
                break;
            }

        }

        if ( hc05 == null ) {
            Toast.makeText(currentActivity, "Dispositivo no encontrado", Toast.LENGTH_SHORT).show();
            return;
        }

        mmConnectThread = new ConnectThread(hc05);
        mmConnectThread.start();

    }

    public void serialWriteString(String message) {
        byte buffer[] = message.getBytes();
        this.serialWriteBytes(buffer);
        Log.d(TAG, "Caracter envíado");
    }

    public void close() {
        if ( mmConnectThread != null ) {
            mmConnectThread.cancel();
            mmConnectThread = null;
        }

        if ( mmConnectedThread != null ) {
            mmConnectedThread.cancel();
            mmConnectedThread = null;
        }

        state = STATE_NONE;

    }

    private void serialWriteBytes(byte[] b){
        ConnectedThread w;
        synchronized (this) {
            if ( state != STATE_CONNECTED )
                return;
            w = mmConnectedThread;
        }
        w.write(b);

    }

    private synchronized void manageMyConnectedSocket() {

        // Si hay algún thread ejecutándose lo detenemos
        if ( mmConnectThread != null ) {
            mmConnectThread.cancel();
            mmConnectThread = null;
        }

        if ( mmConnectedThread != null ) {
            mmConnectedThread.cancel();
            mmConnectedThread = null;
        }

        // Comenzamos nuestro flujo de entreda y salida
        mmConnectedThread = new ConnectedThread();
        mmConnectedThread.start();

    }

    private class ConnectThread extends Thread {

        public ConnectThread(BluetoothDevice device) {
            // Use a temporary object that is later assigned to mmSocket
            // because mmSocket is final.
            BluetoothSocket tmp = null;

            try {
                // Get a BluetoothSocket to connect with the given BluetoothDevice.
                // mUUID is the app's UUID string, also used in the server code.
                tmp = device.createRfcommSocketToServiceRecord(mUUID);
                Log.d(TAG, "Canal establecido correctamente");
            } catch (IOException e) {
                Log.e(TAG, "Socket's create() method failed", e);
            }

            mmSocket = tmp;
            state = STATE_CONNECTING;

        }

        @Override
        public void run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter.cancelDiscovery();

            try {
                // Connect to the remote device through the socket. This call blocks
                // until it succeeds or throws an exception.
                mmSocket.connect();
                Log.d(TAG, "Conexión establecida");
            } catch (IOException connectException) {
                // Unable to connect; close the socket and return.
                try {
                    mmSocket.close();
                } catch (IOException closeException) {
                    Log.e(TAG, "Could not close the client socket", closeException);
                }
                state = STATE_NONE;
                return;
            }

            synchronized (this) {
                mmConnectThread = null;
            }

            manageMyConnectedSocket();

        }

        // Closes the client socket and causes the thread to finish.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the client socket", e);
            }
        }

    }

    private class ConnectedThread extends Thread {

        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread() {

            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams; using temp objects because
            // member streams are final.
            try {
                tmpIn = mmSocket.getInputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating input stream", e);
            }
            try {
                tmpOut = mmSocket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when creating output stream", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            state = STATE_CONNECTED;

        }

        @Override
        public void run() {
            mmBuffer = new byte[1024];
            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                try {
                    ByteArrayOutputStream byteArray = new ByteArrayOutputStream();
                    // Read from the InputStream.
                    for (numBytes = mmInStream.read(mmBuffer); numBytes != -1; numBytes = mmInStream.read(mmBuffer))
                        byteArray.write(numBytes);

                    String res = byteArray.toString();

                    Log.d(TAG, res);
                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }
            }

        }

        // Call this from the main activity to send data to the remote device.
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);
            }

        }

        // Call this method from the main activity to shut down the connection.
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }

    }

}


