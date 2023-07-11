package org.galexander.sshd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.widget.RemoteViews;
import android.widget.Toast;

import java.io.File;

import it.pgp.xfiles.utils.RootHandler;

public class SimpleSSHDService extends Service {

	public static Process currentSshd;
	private static int sshd_pid = 0;
	private static boolean foregrounded = false;

	@Override
	public void onCreate() {
		super.onCreate();
		Prefs.init(this);
		stop_sshd();	/* it would be stale anyways */
	}

	public int onStartCommand(Intent intent, int flags, int startId) {
		if((intent == null) ||
		    (!intent.getBooleanExtra("stop", false))) {
			if(do_start(getBaseContext())) do_foreground();
		}
		else {
			stop_sshd();
			stop_service();
		}
		return START_NOT_STICKY;
	}

	public IBinder onBind(Intent intent) {
		return null;
	}

		/* unfortunately, android doesn't reliably call this when, i.e.,
		 * the package is upgraded... so it's really pretty useless */
	public void onDestroy() {
		stop_sshd();
		stop_service();
		super.onDestroy();
	}

	private void do_foreground() {
		foregrounded = Prefs.get_foreground();
		if (foregrounded) {
			create_notification_channel();

			RemoteViews rv = new RemoteViews(getPackageName(),
					R.layout.notification);
			rv.setImageViewResource(R.id.n_icon, R.drawable.icon);
			rv.setTextViewText(R.id.n_text,
				"SimpleSSHD listening on " +
				SimpleSSHD.get_ip(false) +
				":" + Prefs.get_port());
			PendingIntent pi = PendingIntent.getActivity(this, 0,
				new Intent(this, SimpleSSHD.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
			Notification n = new NotificationCompat.Builder(
						this, "main")
				.setSmallIcon(R.drawable.notification_icon)
				.setTicker("SimpleSSHD")
				.setContent(rv)
				.setContentIntent(pi)
				.setOngoing(true)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setLocalOnly(true)
				.setVisibility(
					NotificationCompat.VISIBILITY_PUBLIC)
				.build();
			startForeground(1, n);
		}
	}

	private static NotificationChannel notificationChannel;
	private void create_notification_channel() {
		if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			if(notificationChannel == null) {
				notificationChannel = new NotificationChannel(
						"main", "SimpleSSHD",
						NotificationManager.IMPORTANCE_LOW);
				notificationChannel.enableLights(false);
				notificationChannel.enableVibration(false);
				notificationChannel.setSound(null, null);
				((NotificationManager)getSystemService(Service.NOTIFICATION_SERVICE)).createNotificationChannel(notificationChannel);
			}
		}
	}

	public static boolean is_started() {
		return (sshd_pid != 0);
	}

	private static void stop_sshd() {
		if(currentSshd != null) {
			// TODO this won't work with root
			android.os.Process.sendSignal(sshd_pid,2); // SIGINT
			currentSshd = null;
			sshd_pid = 0;
			if(SimpleSSHD.curr != null) SimpleSSHD.curr.update_startstop();
		}
	}

	private void stop_service() {
		if(foregrounded) {
			stopForeground(true);
			foregrounded = false;
		}
		stopSelf();
	}

	private static boolean do_start(Context context) {
		stop_sshd();
		new File(Prefs.get_path()).mkdirs();

		File workingDir = new File(context.getApplicationInfo().nativeLibraryDir);
		File sshdPath = new File(workingDir, "libssshd.so");

		Exception lastException = null;
		Process p = null;
		try {
			p = RootHandler.executeCommandSimple(sshdPath.getAbsolutePath(), workingDir, Prefs.get_run_as_root(),
					""+Prefs.get_port(),
					Prefs.get_path(),
					Prefs.get_home(),
					Prefs.get_ssh_server_password()
					);
		}
		catch(Exception e) {
			e.printStackTrace();
			lastException = e;
		}

		currentSshd = p;
		sshd_pid = p == null ? 0 : (int)RootHandler.getPidOfProcess(p);

		String errmsg = "";
		boolean alive = true;
		if(p == null) {
			alive = false;
			errmsg = lastException.getMessage();
		}
		else {
			try {
				int exitValue = p.exitValue();
				alive = false;
				errmsg = ""+exitValue;
			}
			catch(Exception ignored) {}
		}

		if(alive)
			if(SimpleSSHD.curr != null) SimpleSSHD.curr.update_startstop();
		else Toast.makeText(context, "Unable to start ssh server: "+errmsg, Toast.LENGTH_SHORT).show();
		return alive;
	}

	public static void do_startService(Context ctx, boolean stop) {
		Intent i = new Intent(ctx, SimpleSSHDService.class);
		if (stop) {
			i.putExtra("stop", true);
		}
		Prefs.init(ctx);
		ctx.startService(i);
	}
}
