package org.galexander.sshd;

import android.util.Log;
import android.widget.EditText;

import java.io.File;
import java.util.Scanner;

import it.pgp.xfiles.utils.RootHandler;

public class UpdaterThread extends Thread {
	Process tail;
	public EditText log_view; // when the activity is destroyed and recreated while the service is active, set this again to continue showing the log

	public UpdaterThread(EditText log_view) {
		this.log_view = log_view;
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
					SimpleSSHD curr = SimpleSSHD.curr;
					if(curr == null) break;
					else curr.append_line_to_log_view(log_view, line+"\n");
				}
				Log.d(getClass().getName(), "Updater thread ended");
			}
		}
		catch(Exception e) {
			e.printStackTrace();
		}
	}

	@Override
	public void interrupt() {
		android.os.Process.sendSignal((int)RootHandler.getPidOfProcess(tail), 2); // SIGINT
	}
}
