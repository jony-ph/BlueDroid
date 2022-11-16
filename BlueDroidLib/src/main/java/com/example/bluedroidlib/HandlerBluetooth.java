package com.example.bluedroidlib;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.nfc.Tag;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Set;
import java.util.UUID;

public class HandlerBluetooth {

    private static Activity currentActivity;

    private ConnectThread mmConnectThread;
    private ConnectedThread mmConnectedThread;

    private BluetoothSocket mmSocket;
    private static BluetoothAdapter bluetoothAdapter;

    public char inputArduino; // Input desde Arduino
    public boolean isConnected = false;

    static final UUID mUUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String TAG = "MY_APP_DEBUG_TAG";
    private static final int REQUEST_ENABLE_BT = 200;

    // Estados de conexión
    private static final int STATE_NONE = 0;
    private static final int STATE_LISTEN = 1;
    private static final int STATE_CONNECTING = 2;
    private static final int STATE_CONNECTED = 3;

    private int state;

    // Obtener activity actual de Unity
    public static  void receiveUnityActivity(Activity UnityActivity) {
        currentActivity = UnityActivity;
    }

    public void connect() {

        if( isConnected ) {
            Log.d(TAG, "Ya hay una conexión activa");
            return;
        }

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();

        // Comprobar que BT sea compatible
        if ( bluetoothAdapter == null ) {
            Toast.makeText(currentActivity, " Bluetooth no compatible", Toast.LENGTH_SHORT).show();
            return;
        }

        // Si el el Bluetooth está desactivado, pedimos permiso de encender
        if ( !bluetoothAdapter.isEnabled() ) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            currentActivity.startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
        }
        Log.d(TAG, "Requisitos cumplidos");

        // Obtener HC-05 y realizar proceso de conexión por Bluetooth
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        BluetoothDevice hc05 = null;

        if ( pairedDevices.size() <= 0 ) {
            Log.e(TAG, "No se encontraron dispositivos vinculados");
            return;
        }

        // Obtiene nombre y dirección MAC de cada dispositivo emparejado
        for (BluetoothDevice device : pairedDevices) {
            String deviceName = device.getName();
            String deviceHardwareAddress = device.getAddress(); // MAC address

            if ( deviceHardwareAddress.equals("98:D3:61:F6:23:62") ) {
                hc05 = device;
                Log.d(TAG, deviceName + " encontrado");
                break;
            }

        }

        if ( hc05 == null ) {
            Toast.makeText(currentActivity, "Dispositivo no encontrado", Toast.LENGTH_SHORT).show();
            return;
        }

        // Si se encuentra el dispositivo HC-05 entonces comienza conexión en un subproceso
        mmConnectThread = new ConnectThread(hc05);
        mmConnectThread.start();

    }

    private class ConnectThread extends Thread {

        public ConnectThread(BluetoothDevice device) {
            BluetoothSocket tmp = null;

            try {
                // Obtenemos un BluetoothSocket para conectarse con el BluetoothDevice dado.
                // mUUID es la cadena UUID de la aplicación, también utilizada en el código del servidor.
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
            // Se cancela el descubrimiento porque, de lo contrario, se realentizará la conexión
            bluetoothAdapter.cancelDiscovery();

            try {
                // Conectamos el dispositivo a través del Socket. Esta llamada se bloquea hasta
                // que tiene éxito o genera un excepción
                mmSocket.connect();
                isConnected = true;
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

            // Comenzar transferencia de datos
            transferData();

        }

        // Cerrar conexión
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
        private byte[] mmBuffer; // mmBuffer store for the stream

        public ConnectedThread() {
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Obtener el flujo de los input y output; usando objetos temporales porque
            // los flujos de miembros son constantes.
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
            mmBuffer = new byte[1];
            int numBytes; // Bytes devueltos por read()

            // Se mantiene escuchando el input hasta que ocurra una excepción
            while (true) {
                try {
                    numBytes = mmInStream.read(mmBuffer);
                    if ( numBytes < 1 )
                        return;

                    inputArduino = (char) mmBuffer[0];
                    Log.d(TAG, "Caracter: " + inputArduino);

                } catch (IOException e) {
                    Log.d(TAG, "Input stream was disconnected", e);
                    break;
                }

            }

        }

        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) {
                Log.e(TAG, "Error occurred when sending data", e);
            }

        }

        // Cerrar conexión
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "Could not close the connect socket", e);
            }
        }

    }

    private synchronized void transferData() {
        // Si hay algún thread ejecutándose lo detenemos
        if ( mmConnectThread != null ) {
            mmConnectThread.cancel();
            mmConnectThread = null;
        }

        if ( mmConnectedThread != null ) {
            mmConnectedThread.cancel();
            mmConnectedThread = null;
        }

        // Comenzamos flujo de entrada y salida en un subproceso
        mmConnectedThread = new ConnectedThread();
        mmConnectedThread.start();

    }

    // Llamar para envíar datos a dipositivo remoto
    public void serialWriteString(String message) {
        if( mmConnectedThread == null)
            return;

        byte buffer[] = message.getBytes();
        this.serialWriteBytes(buffer);
        Log.d(TAG, "Caracter envíado" + buffer);

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

    public void close() {
        if ( mmConnectThread != null ) {
            mmConnectThread.cancel();
            mmConnectThread = null;
        }

        if ( mmConnectedThread != null ) {
            mmConnectedThread.cancel();
            mmConnectedThread = null;
        }

        isConnected = false;
        state = STATE_NONE;

    }

}



