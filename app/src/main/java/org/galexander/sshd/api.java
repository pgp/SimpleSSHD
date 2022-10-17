package org.galexander.sshd;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;


public class api {
	private static AtomicReference<Thread> curr_thread = null;

		/* assumes JNI api_mkfifo() has already completed */
	public static void start(final Context ctx, final String fn) {
		if (fn == null) return;
		if (curr_thread.get() != null) return;
		Thread t = new Thread() { public void run() {
						thread_main(ctx, fn); } };
		if (curr_thread.compareAndSet(null, t)) {
			t.start();
		}
	}

	private static String get_line(FileInputStream fis) throws IOException {
		byte[] ret = new byte[512];
		int len = 0;
		while (true) {
			if (len+5 > ret.length) {
				ret = Arrays.copyOf(ret, len+512);
			}
			int c = fis.read();
			if ((c == -1) || (c == '\n') || (c == 0)) {
				return new String(ret, 0, len);
			}
			ret[len++] = (byte)c;
		}
	}

	private static String get_command(String fn) {
		try {
			FileInputStream fis = new FileInputStream(fn);
			String ret = get_line(fis);
			fis.close();
			return ret;
		} catch (Exception e) {
			return null;
		}
	}

	private static List<String> split_string(String s) {
		LinkedList<String> ret = new LinkedList<String>();
		int ofs = 0;
		final int len = s.length();
		while (ofs < len) {
			while ((ofs < len) &&
			       Character.isSpace(s.charAt(ofs))) {
				ofs++;
			}
			if (s.startsWith("-- ", ofs)) {
				ret.add(s.substring(ofs+3));
				break;
			} else if (ofs < len) {
				int start = ofs;
				while ((ofs < len) &&
				       !Character.isSpace(s.charAt(ofs))) {
					ofs++;
				}
				ret.add(s.substring(start, ofs-start));
			}
		}
		return ret;
	}

	private static void parse_command(Context ctx, String s) {
		List<String> parms = split_string(s);
		Iterator<String> pi = parms.iterator();
		String cmd = pi.next();

		if (cmd.equals("activity")) {
			String url = pi.next();
			Uri uri = (new Uri.Builder()).path(url).build();
			ctx.startActivity(new Intent(Intent.ACTION_VIEW, uri));
		}
	}

	private static void thread_main(Context ctx, String fn) {
		int nulls = 0;
		while (nulls < 5) {		/* retry limit */
			String s = get_command(fn);
			if (s == null) {
				nulls++;
			} else {
				nulls = 0;
				parse_command(ctx, s);
			}
		}
		curr_thread.compareAndSet(Thread.currentThread(), null);
	}
}
