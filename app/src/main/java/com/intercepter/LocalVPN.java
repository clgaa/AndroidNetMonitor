/*
** Copyright 2015, Mohamed Naufal
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.intercepter;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.support.v4.content.LocalBroadcastManager;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import java.util.concurrent.ConcurrentHashMap;

import com.intercepter.task.GetApiTask;
import com.intercepter.util.Constant;
import com.net.monitor.LocalVPNService;
import com.net.monitor.R;


public class LocalVPN extends ActionBarActivity {
    private static final int VPN_REQUEST_CODE = 0x0F;

    private boolean waitingForVPNStart;
    private HttpInterceptor interceptor;


    private BroadcastReceiver vpnStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (LocalVPNService.BROADCAST_VPN_STATE.equals(intent.getAction())) {
                if (intent.getBooleanExtra("running", false)) {
                    waitingForVPNStart = false;
                    interceptor.startIntercept();
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_local_vpn);
        loadInterceptorApiList();
        final Button vpnButton = (Button) findViewById(R.id.vpn);
        vpnButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startVPN();
            }
        });
        waitingForVPNStart = false;
        LocalBroadcastManager.getInstance(this).registerReceiver(vpnStateReceiver,
                new IntentFilter(LocalVPNService.BROADCAST_VPN_STATE));
        ConcurrentHashMap<String, String> api = new ConcurrentHashMap<>();
        api.put("dSetOnlineStatus", "{\"code\":304,\"msg\":\"CACHED\",\"data\":[],\"ns\":\"gulf_driver\",\"key\":\"dd9a7bfb6ccbe1a73314b4e88ab9a5f\",\"md5\":\"\"}");
        api.put("dGetListenMode", "{\"errno\":0,\"errmsg\":\"SUCCESS\",\"listen_mode\":1,\"book_stime\":-1,\"book_etime\":-1,\"listen_carpool_mode\":1,\"nova_enabled\":0,\"listen_distance\":0,\"auto_grab_flag\":1,\"grab_mode\":1,\"compet_show_dest\":1,\"can_compet_order_num\":-1,\"addr_info\":{\"dest_name\":\"\",\"dest_address\":\"\",\"dest_lng\":\"0.000000\",\"dest_lat\":\"0.000000\",\"dest_type\":0},\"receive_level\":\"600,500\",\"receive_level_type\":96,\"show_carpool\":0,\"show_nova\":0,\"distance_config\":\"\",\"show_auto_grab\":0,\"show_assign\":0,\"show_dest\":1,\"car_level\":{\"default_level\":\"600\",\"level_info\":\"\\u666e\\u901a\"}}");
        interceptor = new HttpInterceptor("api.udache.com", api);

    }

    private void loadInterceptorApiList() {
        new Thread(new GetApiTask(Constant.PATH, ".*\\.txt")).start();
    }

    private void startVPN() {
        Intent vpnIntent = VpnService.prepare(this);
        if (vpnIntent != null)
            startActivityForResult(vpnIntent, VPN_REQUEST_CODE);
        else
            onActivityResult(VPN_REQUEST_CODE, RESULT_OK, null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == VPN_REQUEST_CODE && resultCode == RESULT_OK) {
            waitingForVPNStart = true;
            startService(new Intent(this, LocalVPNService.class));
            enableButton(false);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();

        enableButton(!waitingForVPNStart && !LocalVPNService.isRunning());
    }

    private void enableButton(boolean enable) {
        final Button vpnButton = (Button) findViewById(R.id.vpn);
        if (enable) {
            vpnButton.setEnabled(true);
            vpnButton.setText(R.string.start_vpn);
        } else {
            vpnButton.setEnabled(false);
            vpnButton.setText(R.string.stop_vpn);
        }
    }
}
