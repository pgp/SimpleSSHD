package org.galexander.sshd;

import java.io.File;

public class UpdaterThread extends Thread {
	/* poll for changes to the dropbear.err file */
	public void run() {
		File f = new File(Prefs.get_path(), "dropbear.err");
		long lastmod = 0;
		long lastlen = 0;
		for(;;) {
			if(isInterrupted()) break;

			if(SimpleSSHD.curr == null) break;
			else {
				long mod = f.lastModified();
				long len = f.length();
				if((mod != lastmod) || (len != lastlen)) {
					SimpleSSHD.curr.update_log();
					lastmod = mod;
					lastlen = len;
				}
				try{
					sleep(1000);
				}
				catch(InterruptedException e) {
					break;
				}
			}
		}
	}
}
