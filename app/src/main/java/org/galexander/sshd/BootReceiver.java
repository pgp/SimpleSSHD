package org.galexander.sshd;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.Context;

public class BootReceiver extends BroadcastReceiver {
	public void onReceive(Context context, Intent intent) {
		Prefs.init(context);
		if (Prefs.get_onboot()) {
			SimpleSSHDService.my_startService(context,
				new Intent(context, SimpleSSHDService.class));
		}
	}
}
