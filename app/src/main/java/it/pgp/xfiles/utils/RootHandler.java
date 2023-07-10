package it.pgp.xfiles.utils;

import android.util.Log;

import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;


public class RootHandler {

    public static synchronized long getPidOfProcess(Process p) {
        long pid;

        try {
            // on Android: java.lang.ProcessManager$ProcessImpl
//            if (p.getClass().getName().equals("java.lang.UNIXProcess")) {
            Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            pid = f.getLong(p);
            f.setAccessible(false);
            return pid;
//            }
        } catch (Exception e) {
            return -1;
        }
    }

    public static boolean isRootAvailableAndGranted = false;

    public static boolean isRooted() {
        return findBinary("su");
    }

    private static boolean findBinary(String binaryName) {
        boolean found = false;
        String[] places = {"/sbin/", "/system/bin/", "/system/xbin/", "/data/local/xbin/",
                "/data/local/bin/", "/system/sd/xbin/", "/system/bin/failsafe/", "/data/local/"};
        for (String where : places) {
            if ( new File( where + binaryName ).exists() ) {
                found = true;
                Log.d(RootHandler.class.getName(),"su binary found at "+where);
                break;
            }
        }
        return found;
    }

    public static Process executeCommandSimple(String command, File workingDir, boolean runAsSuperUser, String... args) throws IOException {
        String s = "";
        s += command;
        if (args != null) for (String arg : args) s += " " + arg;

        Process p;
        if (runAsSuperUser) {
            p = Runtime.getRuntime().exec("su");
            DataOutputStream dos = new DataOutputStream(p.getOutputStream());
            if (workingDir != null) {
                dos.writeBytes("cd " + workingDir +"\n");
            }
            dos.writeBytes(s + "\n");
            dos.writeBytes("exit\n");
            dos.flush();
            dos.close();
        } else {
            p = (workingDir==null)?
                    Runtime.getRuntime().exec(s):
                    Runtime.getRuntime().exec(s,null,workingDir);
        }

//        lastStartedPid = getPidOfProcess(p);

        return p; // p started, not joined

        // exitValue to be called later
//        int exitValue = 0;
//        try {
//            exitValue = p.waitFor();
//        }
//        catch (InterruptedException ignored) {}

//        StringBuilder output = new StringBuilder();
//        // no console output expected from process
//        BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
//
//        String line;
//        while ((line = reader.readLine()) != null) {
//            output.append(line).append("\n");
//        }
//
//        Log.d(RootHandler.class.getName(), "***BEGIN Parent process output:***\n" + output.toString() + "\n***END Parent process output***\nExit value: " + exitValue);
    }
}
