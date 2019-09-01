package org.galexander.sshd;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;

public class StopReceiver extends BroadcastReceiver {
	public void onReceive(Context context, Intent intent) {
                SimpleSSHDService.my_startService(context,
			new Intent(context, SimpleSSHDService.class)
				.putExtra("stop", true));
	}
}
