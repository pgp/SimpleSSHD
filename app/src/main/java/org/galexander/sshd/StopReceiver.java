package org.galexander.sshd;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;

public class StopReceiver extends BroadcastReceiver {
	public void onReceive(Context context, Intent intent) {
                SimpleSSHDService.do_startService(context, /*stop=*/true);
	}
}
