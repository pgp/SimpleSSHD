#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/wait.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <ctype.h>
#include <errno.h>

const char *conf_path = "", *conf_shell = "", *conf_home = "", *conf_env = "", *conf_lib = "";
int conf_rsyncbuffer = 0;

/* NB - this will leak memory like crazy if called often.... */
const char *
conf_path_file(const char *fn)
{
	char *ret = malloc(strlen(conf_path)+strlen(fn)+20);
	sprintf(ret, "%s/%s", conf_path, fn);
	return ret;
}


extern int dropbear_main(int argc, char **argv);
extern const char* global_ssh_server_password;


/* split str into argv entries, honoring " and \ (but nothing else) */
static int
split_cmd(const char *in, char **argv, int max_argc)
{
	char curr[1000];
	int curr_len = 0;
	int in_quotes = 0;
	int argc = 0;

	if (!in) {
		argv[argc] = NULL;
		return 0;
	}
	while (1) {
		char c = *in++;
		if (!c || (curr_len+10 >= sizeof curr) ||
		    (!in_quotes && isspace(c))) {
			if (curr_len) {
				curr[curr_len] = 0;
				if (argc+2 >= max_argc) {
					break;
				}
				argv[argc++] = strdup(curr);
				curr_len = 0;
			}
			if (!c) {
				break;
			}
		} else if (c == '"') {
			in_quotes = !in_quotes;
		} else {
			if (c == '\\') {
				c = *in++;
				switch (c) {
					case 'n': c = '\n'; break;
					case 'r': c = '\r'; break;
					case 'b': c = '\b'; break;
					case 't': c = '\t'; break;
					case 0: in--; break;
				}
			}
			curr[curr_len++] = c;
		}
	}
	argv[argc] = NULL;
	return argc;
}


/* this makes sure that no previously-added atexit gets called (some users have
 * an atexit registered by libGLESv2_adreno.so */
static void
null_atexit(void)
{
	_Exit(0);
}


// wrapper signature:
// args: [portNum] [conf_path] [conf_home] [password] -- ignore extras for now
int main(int argc, char* argv[]) {
    if(argc < 4) {
        puts("Usage: sshd [portNum] [conf_path] [conf_home] [optionalPassword]");
        _Exit(0);
    }
    char* portstr = argv[1]; // context getFilesDir, can be passed as env var XSSHD_CONF
    conf_path = argv[2]; // context getFilesDir, can be passed as env var XSSHD_CONF
    conf_home = argv[3]; // context getFilesDir or /sdcard, can be passed as env var XSSHD_HOME
    // conf_lib as working directory, pass from RootHandler (parent of libsshd-jni.so)

    char* conf_pass = NULL;
    if(argc >= 5) conf_pass = argv[4];

    // actual args: sshd -R -F -p [port]
    { // logic after fork()
        char *nargv[100] = { "sshd", NULL };
        int nargc = 1;
        const char *logfn;
        const char *logfn_old;
        int logfd;
        int retval;
        int i;

        atexit(null_atexit);

        logfn = conf_path_file("dropbear.err");
        logfn_old = conf_path_file("dropbear.err.old");
        unlink(logfn_old);
        rename(logfn, logfn_old);
        unlink(logfn);
        logfd = open(logfn, O_CREAT|O_WRONLY, 0666);
        if (logfd != -1) {
            /* replace stderr, so the output is preserved... */
            dup2(logfd, 2);
        }
        for (i = 3; i < 255; i++) {
            /* close all of the dozens of fds that android typically
             * leaves open */
            close(i);
        }

        nargv[nargc++] = "-R";	/* enable DROPBEAR_DELAY_HOSTKEY */
        nargv[nargc++] = "-F";	/* don't redundant fork to background */
        if(portstr) {
            nargv[nargc++] = "-p";
            nargv[nargc++] = portstr;
        }
        fprintf(stderr, "starting dropbear\n");

        if(conf_pass) global_ssh_server_password = conf_pass;

        fprintf(stderr, "actual args to dropbear_main:\n");
        for(i=0; i<nargc; i++) fprintf(stderr, "%s\n", nargv[i]);

        retval = dropbear_main(nargc, nargv);
        /* NB - probably not reachable */
        fprintf(stderr, "dropbear finished (%d)\n", retval);
        exit(0);
    }
}


