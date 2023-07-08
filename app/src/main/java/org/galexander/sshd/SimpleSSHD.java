package org.galexander.sshd;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.ClipboardManager;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

public class SimpleSSHD extends Activity
{
	private static final Object lock = new Object();
	private EditText log_view;
	private Button startstop_view;
	private TextView ip_view;
	public static SimpleSSHD curr = null;
	public static String app_private = null;
	private UpdaterThread updater = null;
	public final boolean is_tv = this instanceof SimpleSSHDTV;

	public void onCreate(Bundle savedInstanceState) {
		if(is_tv) requestWindowFeature(Window.FEATURE_NO_TITLE);
		super.onCreate(savedInstanceState);
		app_private = getFilesDir().toString();
		Prefs.init(this);
		setContentView(is_tv ? R.layout.main_tv : R.layout.main);
		log_view = (EditText)findViewById(R.id.log);
		startstop_view = (Button)findViewById(R.id.startstop);
		ip_view = (TextView)findViewById(R.id.ip);
	}

	public void onResume() {
		super.onResume();
		synchronized (lock) {
			curr = this;
		}
		permission_startup();
		update_startstop_prime();
		updater = new UpdaterThread();
		updater.start();
		ip_view.setText(get_ip(true));

		if (Prefs.get_onopen() && !SimpleSSHDService.is_started()) {
			SimpleSSHDService.do_startService(this, /*stop=*/false);
		}
	}

	public void onPause() {
		synchronized (lock) {
			curr = null;
		}
		updater.interrupt();
		super.onPause();
	}

	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.main_menu, menu);
		return true;
	}

	public boolean onOptionsItemSelected(MenuItem item) {
		switch (item.getItemId()) {
			case R.id.settings:
				settings_clicked(null);
				return true;
			case R.id.copypriv:
				copypriv_clicked(null);
				return true;
			case R.id.resetkeys:
				resetkeys_clicked(null);
				return true;
			case R.id.doc:
				doc_clicked(null);
				return true;
			case R.id.about:
				about_clicked(null);
				return true;
			default:
				return super.onOptionsItemSelected(item);
		}
	}


	/* these can be called as the _clicked() variant on Android TV, or
	 * through the options menu on regular Android */
	public void settings_clicked(View v) {
		startActivity(new Intent(this, Settings.class));
	}
	public void copypriv_clicked(View v) {
		copy_app_private();
	}
	public void resetkeys_clicked(View v) {
		reset_keys();
	}
	public void doc_clicked(View v) {
		try {
			Intent i = new Intent(Intent.ACTION_VIEW);
			i.setData(Uri.parse(DocActivity.url));
			startActivity(i);
		} catch (Exception e) {
			startActivity(new Intent(this, (is_tv
				? DocActivityTV.class : DocActivity.class)));
		}
	}
	public void about_clicked(View v) {
		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setCancelable(true);
		b.setPositiveButton("OK", (d, w) -> {});
		b.setIcon(android.R.drawable.ic_dialog_info);
		b.setTitle("About");
		b.setMessage(
				"SimpleSSHD version " + my_version() +
						"\ndropbear 2020.81" +
						"\nscp/sftp from OpenSSH 6.7p1" +
						"\nrsync 3.1.1" +
						"\nPGP's mod version: 20230708");
		b.show();
	}


	private void update_startstop_prime() {
		if (SimpleSSHDService.is_started()) {
			startstop_view.setText(Prefs.get_onopen() ? "QUIT" : "STOP");
			startstop_view.setTextColor(is_tv ? 0xFFFF6666 : 0xFF881111);
		} else {
			startstop_view.setText("START");
			startstop_view.setTextColor(is_tv ? 0xFF44FF44 : 0xFF118811);
		}
	}

	private static void run_on_ui(Runnable r) {
		synchronized(lock) {
			if(curr != null) curr.runOnUiThread(r);
		}
	}

	public static void update_startstop() {
		run_on_ui(() -> {
			synchronized(lock) {
				if(curr != null) curr.update_startstop_prime();
			}
		});
	}

	public void startstop_clicked(View v) {
		boolean already_started = SimpleSSHDService.is_started();
		SimpleSSHDService.do_startService(this, already_started);
		if(already_started && Prefs.get_onopen()) finish();
	}

	private void update_log_prime() {
		String[] lines = new String[50];
		int curr_line = 0;
		boolean wrapped = false;
		try {
			File f = new File(Prefs.get_path(), "dropbear.err");
			if(f.exists()) {
				BufferedReader r = new BufferedReader(new FileReader(f));
				try {
					String l;
					while((l = r.readLine()) != null) {
						lines[curr_line++] = l;
						if(curr_line >= lines.length) {
							curr_line = 0;
							wrapped = true;
						}
					}
				}
				finally {
					r.close();
				}
			}
		}
		catch(Exception ignored) { }
		int i;
		i = (wrapped ? curr_line : 0);
		String output = "";
		do {
			output = output + lines[i] + "\n";
			i++;
			i %= lines.length;
		} while (i != curr_line);
		log_view.setText(output);
		log_view.setSelection(output.length());
	}

	public static void update_log() {
		run_on_ui(() -> {
			synchronized (lock) {
				if(curr != null) curr.update_log_prime();
			}
		});
	}

	public static String get_ip(boolean pretty) {
		String ret = "";
		int num_ips = 0;
		try {
			List<NetworkInterface> interfaces = Collections.list(NetworkInterface.getNetworkInterfaces());
			for (NetworkInterface intf : interfaces) {
				List<InetAddress> addrs = Collections.list(intf.getInetAddresses());
				for (InetAddress addr : addrs) {
					String ip = addr.getHostAddress();
					if (!addr.isLoopbackAddress() &&
					    !ip.startsWith("fe80")) {
						int i = ip.indexOf('%');
						if (i != -1) {
							ip = ip.substring(0,i);
						}
						if (!pretty) {
							return ip;
						}
						if (num_ips++ >= 5) {
							return ret+"...";
						}
						if (num_ips > 1) {
							ret += "\n";
						}
						ret += "IP: " + ip;
					}
				}
			}
		}
		catch (Exception ignored) { } // for now swallow exceptions
		return ret;
	}

	private void copy_app_private() {
		new AlertDialog.Builder(this)
		  .setCancelable(true)
		  .setPositiveButton("OK", null)
		  .setNegativeButton("Copy",
				  (d, w) -> {
					  ClipboardManager cl = (ClipboardManager)
						  getSystemService(
						  Context.CLIPBOARD_SERVICE);
					  if (cl != null) {
						  cl.setText(app_private);
					  }
				  })
		  .setIcon(android.R.drawable.ic_dialog_info)
		  .setTitle("App-private path")
		  .setMessage(app_private)
		  .show();
	}

	private void do_reset_keys() {
		new File(Prefs.get_path(), "authorized_keys").delete();
	}

	private void reset_keys() {
		AlertDialog.Builder b = new AlertDialog.Builder(this);
		b.setCancelable(true);
		b.setPositiveButton("Yes", (d, w) -> do_reset_keys());
		b.setNegativeButton("No", null);
		b.setIcon(android.R.drawable.ic_dialog_alert);
		b.setTitle("Reset Keys");
		b.setMessage("Delete the authorized_keys file? (then you will only be able to login with single-use passwords)");
		b.show();
	}

	public String my_version() {
		try {
			return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
		}
		catch(Exception e) {
			return "UNKNOWN";
		}
	}

	public boolean areStoragePermissionsGranted() {
		// EITHER Android < 6
		// OR storage permissions granted on Android 6-10
		// OR all-files permissions granted on Android 11+
		return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
				(Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
						checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) ||
						(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager());
	}

	public void requestStoragePermissions() {
		if(Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
			requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
		else {
			// with Android >= 11, by having this signature permission granted by user, we can access all files (both read and write, even external sd and usb drives)
			Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName()));
			startActivityForResult(intent, 0);
		}
	}

	private void permission_startup() {
		if(!areStoragePermissionsGranted()) requestStoragePermissions();
	}

	private void toast(String s) {
		Toast.makeText(this, s, Toast.LENGTH_LONG).show();
	}

	public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
		Prefs.set_requested();	/* whatever result, don't ask again */
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if(requestCode == 0) {
			if(!areStoragePermissionsGranted()) {
				toast("Storage permissions not granted, exiting...");
				finishAffinity();
			}
		}
	}
}
