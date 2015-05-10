package com.nebososo.dolphindroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.Vector;


public class ConnectionActivity extends Activity {

    private Button connect_button;
    private Button about_button;
    private ListView server_list_view;
    private ArrayList<String> server_names = new ArrayList<String>();
    private ActiveServersList activeServers = new ActiveServersList();
    private Vector<UdpwiiServer> localActiveServersList = new Vector<UdpwiiServer>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_connection);

        server_list_view = (ListView) findViewById(R.id.server_list_view);
        connect_button = (Button) findViewById(R.id.connect_button);
        about_button = (Button) findViewById(R.id.about_button);

        final ArrayAdapter<String> server_names_adapter = new ArrayAdapter<String>(this,
                android.R.layout.simple_list_item_1, server_names);
        server_list_view.setAdapter(server_names_adapter);

        AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(this);
        alertDialogBuilder.setMessage(R.string.about_content);
        alertDialogBuilder.setTitle(R.string.about);
        final AlertDialog aboutDialog = alertDialogBuilder.create();

        about_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                aboutDialog.show();
            }
        });

        connect_button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
            }
        });

        TimerTask maintainServerListTask = new TimerTask() {
            @Override
            public void run() {
                activeServers.cleanup();

                 runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (activeServers.getServerListIfChanged(localActiveServersList)) {
                            server_names.clear();
                            for (int i = 0; i < localActiveServersList.size(); i++) {
                                server_names.add(localActiveServersList.get(i).name
                                        + " " + localActiveServersList.get(i).index);
                            }
                            server_names_adapter.notifyDataSetChanged();
                        }
                    }
                 });
            }
        };
        Timer maintenanceTimer = new Timer();

        new Thread(new BroadcastListener(activeServers)).start();
        maintenanceTimer.scheduleAtFixedRate(maintainServerListTask, 1000, 1000);
    }
}

class ActiveServersList {
    private Map<Integer, UdpwiiServer> activeServers = new TreeMap<Integer, UdpwiiServer>();
    private boolean changed = false;

    public synchronized void addServer(UdpwiiServer server) {
        if (activeServers.get(server.id) == null) {
            changed = true;
        }
        activeServers.put(server.id, server);
    }

    public synchronized void cleanup() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Integer, UdpwiiServer>> it = activeServers.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, UdpwiiServer> entry = it.next();
            long timestamp = entry.getValue().timestamp;
            /* Entry is 5 seconds or older */
            if (now - timestamp >= 5000) {
                it.remove();
                changed = true;
            }
        }
    }

    public synchronized boolean getServerListIfChanged(Vector<UdpwiiServer> servers) {
        if (!changed) {
            return false;
        }

        servers.clear();
        for (Map.Entry<Integer, UdpwiiServer> entry : activeServers.entrySet()){
            servers.add(entry.getValue());
        }

        changed = false;
        return true;
    }
}

class UdpwiiServer {
    public int port;
    public int id;
    public int index;
    public String name;
    public long timestamp;
    public String address;

    public UdpwiiServer(DatagramPacket packet) {
        byte[] pbuf = packet.getData();
        int name_len = (int) (pbuf[6] & 0xff);

        id = (int) ((pbuf[1] & 0xff) << 8) | (int) (pbuf[2] & 0xff);
        index = (int) pbuf[3] + 1;
        port = (int) ((pbuf[4] & 0xff) << 8) | (int) (pbuf[5] & 0xff);
        name = new String(pbuf, 7, name_len);
        timestamp = System.currentTimeMillis();
        address = packet.getAddress().toString();
    }
}

class BroadcastListener implements Runnable {
    private byte[] buffer = new byte[512];
    private DatagramSocket socket;
    private DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
    private ActiveServersList activeServers;

    public BroadcastListener(ActiveServersList as) {
        activeServers = as;
    }

    public void run() {
        try {
            socket = new DatagramSocket(4431);
        } catch (SocketException e) {
            e.printStackTrace();
        }

        for (;;) {
            try {
                socket.receive(packet);
            } catch (IOException e) {
                e.printStackTrace();
            }
            if (validatePacket()) {
                activeServers.addServer(new UdpwiiServer(packet));
            }
        }
    }

    private boolean validatePacket() {
        byte[] pbuf = packet.getData();

        if (packet.getLength() < 8) {
            return false;
        }

        int name_len = (int) (pbuf[6] & 0xff);
        if (packet.getLength() != 7 + name_len) {
            return false;
        }

        if (pbuf[0] != (byte) 0xdf) {
            return false;
        }

        if (pbuf[4] == 0 && pbuf[5] == 0) {
            return false;
        }

        if (pbuf[3] < 0 || pbuf[3] > 3) {
            return false;
        }

        return true;
    }
}
