package org.galexander.sshd;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;

public class StartReceiver extends BroadcastReceiver {
	public void onReceive(Context context, Intent intent) {
                SimpleSSHDService.my_startService(context,
			new Intent(context, SimpleSSHDService.class));
	}
}
