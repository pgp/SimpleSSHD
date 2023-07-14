package org.galexander.sshd;

import android.util.Log;

import java.io.File;
import java.util.Scanner;

import it.pgp.xfiles.utils.RootHandler;

public class UpdaterThread extends Thread {
	Process tail;
	public final StringBuilder buffer = new StringBuilder();
	private SimpleSSHD activity; // when the activity is destroyed and recreated while the service is active, set this again to continue showing the log

	public UpdaterThread(SimpleSSHD activity) {
		this.activity = activity;
	}

	/* watch changes to the dropbear.err file */
	public void run() {
		try {
			Log.d(getClass().getName(), "Starting updater thread");
			File f = new File(Prefs.get_path(), "dropbear.err");
			tail = new ProcessBuilder("tail", "-f", f.getAbsolutePath()).start(); // tail -F (follow filenames) is not implemented on Android
			try(Scanner scanner = new Scanner(tail.getInputStream())) {
				while(scanner.hasNextLine()) {
					String line = scanner.nextLine();
					buffer.append(line + "\n");
					if(activity != null) activity.refresh_log_view(buffer.toString());
				}
				Log.d(getClass().getName(), "Updater thread ended");
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	public void refreshActivityOnResume(SimpleSSHD activity) {
		this.activity = activity;
		activity.refresh_log_view(buffer.toString());
	}

	public void pauseLoggingOnActivityPause() {
		this.activity = null;
	}

	@Override
	public void interrupt() {
		android.os.Process.sendSignal((int)RootHandler.getPidOfProcess(tail), 2); // SIGINT
	}
}
