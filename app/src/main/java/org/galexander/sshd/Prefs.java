package org.galexander.sshd;

import android.content.SharedPreferences;
import android.content.Context;
import android.preference.PreferenceManager;

public class Prefs {
	private static SharedPreferences pref = null;

	public static void init(Context c) {
		if(pref == null) {
			pref = PreferenceManager.getDefaultSharedPreferences(c);
			set_defaults();
		}
	}

	public static boolean get_onboot() {
		return pref.getBoolean("onboot", false);
	}
	public static boolean get_foreground() {
		return pref.getBoolean("foreground", true);
	}
	public static boolean get_onopen() {
		return pref.getBoolean("onopen", false);
	}
	public static boolean get_rsyncbuffer() {
		return pref.getBoolean("rsyncbuffer", false);
	}
	public static int get_port() {
		try {
			return Integer.parseInt(pref.getString("port", "2222"));
		}
		catch(Exception e) {
			return 2222;
		}
	}
	public static String get_ssh_server_password() {
		return pref.getString("sshserverpassword", "");
	}
	public static String get_path() {
		return pref.getString("path", SimpleSSHD.app_private);
	}
	public static String get_shell() {
		return pref.getString("shell", "/system/bin/sh");
	}
	public static String get_home() {
		return pref.getString("home", SimpleSSHD.app_private);
	}
	public static String get_extra() {
		return pref.getString("extra", "");
	}
	public static String get_env() {
		return pref.getString("env", "");
	}
	public static boolean get_requested() {	/* already requested perms */
		return pref.getBoolean("requested", false);
	}
	public static void set_requested() {
		SharedPreferences.Editor edit = pref.edit();
		edit.putBoolean("requested", true);
		edit.apply();
	}

	/* NB - other defaults can be filled in by either Prefs or Settings as
	 * needed */
	private static void set_defaults() {
		boolean need_path = (pref.getString("path", null) == null);
		boolean need_home = (pref.getString("home", null) == null);
		if(!need_path && !need_home) return;
		SharedPreferences.Editor edit = pref.edit();
		if(need_path) edit.putString("path", SimpleSSHD.app_private);
		if(need_home) edit.putString("home", SimpleSSHD.app_private);
		edit.apply();
	}
}
