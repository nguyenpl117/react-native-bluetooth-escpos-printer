
package cn.jystudio.bluetooth;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices.
 */
public class BluetoothService {
    // Debugging
    private static final String TAG = "BluetoothService";
    private static final boolean DEBUG = true;


    // Name for the SDP record when creating server socket
    private static final String NAME = "BTPrinter";
    //UUID must be this
    // Unique UUID for this application
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
    private BluetoothAdapter mAdapter;

    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
   // public static final int STATE_LISTEN = 1;     // now listening for incoming connections //feathure removed.
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device


    public static final int MESSAGE_STATE_CHANGE = 4;
    public static final int MESSAGE_READ = 5;
    public static final int MESSAGE_WRITE = 6;
    public static final int MESSAGE_DEVICE_NAME = 7;
    public static final int MESSAGE_CONNECTION_LOST = 8;
    public static final int MESSAGE_UNABLE_CONNECT = 9;

    // Key names received from the BluetoothService Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    public static String ErrorMessage = "No_Error_Message";

    private static List<BluetoothServiceStateObserver> observers = new ArrayList<BluetoothServiceStateObserver>();

    /**
     * Constructor. Prepares a new BTPrinter session.
     *
     * @param context The UI Activity Context
     */
    public BluetoothService(Context context) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;

    }

    public void addStateObserver(BluetoothServiceStateObserver observer) {
        observers.add(observer);
    }

    public void removeStateObserver(BluetoothServiceStateObserver observer) {
        observers.remove(observer);
    }

    /**
     * Set the current state of the connection
     *
     * @param state An integer defining the current connection state
     */
    private synchronized void setState(int state, Map<String, Object> bundle) {
        if (DEBUG) Log.d(TAG, "setState() " + getStateName(mState) + " -> " + getStateName(state));
        mState = state;
        infoObervers(state, bundle);
    }
    private String getStateName(int state){
        String name="UNKNOW:" + state;
        if(STATE_NONE == state){
            name="STATE_NONE";
        }else if(STATE_CONNECTED == state){
            name="STATE_CONNECTED";
        }else if(STATE_CONNECTING ==  state){
            name="STATE_CONNECTING";
        }
        return name;
    }

    private synchronized void infoObervers(int code, Map<String, Object> bundle) {
        for (BluetoothServiceStateObserver ob : observers) {
            ob.onBluetoothServiceStateChanged(code, bundle);
        }
    }

    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }


    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     */
    public synchronized void connect(BluetoothDevice device) {
        if (DEBUG) Log.d(TAG, "connect to: " + device);

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        // Cancel any thread attempting to make a connection
       // if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
       // }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING, null);
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    public synchronized void connected(BluetoothSocket socket, BluetoothDevice device) {
        if (DEBUG) Log.d(TAG, "connected");

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();

        Map<String, Object> bundle = new HashMap<String, Object>();
        bundle.put(DEVICE_NAME, device.getName());

        setState(STATE_CONNECTED, bundle);
    }

    /**
     * Stop all threads
     */
    public synchronized void stop() {
        if (DEBUG) Log.d(TAG, "stop");
        setState(STATE_NONE, null);
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        r.write(out);
    }

    /**
     * Indicate that the connection attempt failed.
     */
    private void connectionFailed() {
        setState(STATE_NONE, null);
        infoObervers(MESSAGE_UNABLE_CONNECT, null);
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        infoObervers(MESSAGE_CONNECTION_LOST, null);
    }

    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        public ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                tmp = device.createRfcommSocketToServiceRecord(MY_UUID);
            } catch (IOException e) {e.printStackTrace();
                Log.e(TAG, "create() failed", e);
            }
            if(tmp==null){
                Log.e(TAG, "create() failed: Socket NULL.");
            }
            mmSocket = tmp;
        }

        @Override
        public void run() {
            Log.i(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {e.printStackTrace();
                connectionFailed();
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
                    Log.e(TAG, "unable to close() socket during connection failure", e2);
                }
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            Log.d(TAG, "create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.e(TAG, "temp sockets not created", e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        @Override
        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            int bytes;

            // Keep listening to the InputStream while connected
            while (true) {
                try {
                    byte[] buffer = new byte[256];
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    if (bytes > 0) {
                        // Send the obtained bytes to the UI Activity
                        Map<String, Object> bundle = new HashMap<String, Object>();
                        bundle.put("bytes", bytes);
                        infoObervers(MESSAGE_READ, bundle);
                    } else {
                        Log.e(TAG, "disconnected");
                        connectionLost();
                        break;
                    }
                } catch (IOException e) {
                    Log.e(TAG, "disconnected", e);
                    connectionLost();
                    break;
                }
            }
        }

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
                mmOutStream.flush();//清空缓存
               /* if (buffer.length > 3000) //
                {
                  byte[] readata = new byte[1];
                  SPPReadTimeout(readata, 1, 5000);
                }*/
                Log.i("BTPWRITE", new String(buffer, "GBK"));
                Map<String, Object> bundle = new HashMap<String, Object>();
                bundle.put("bytes", buffer);
                infoObervers(MESSAGE_WRITE, bundle);
            } catch (IOException e) {
                Log.e(TAG, "Exception during write", e);
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
                connectionLost();
            } catch (IOException e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}
