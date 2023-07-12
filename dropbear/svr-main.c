/*
 * Dropbear - a SSH2 server
 * 
 * Copyright (c) 2002-2006 Matt Johnston
 * All rights reserved.
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE. */

#include "includes.h"
#include "dbutil.h"
#include "session.h"
#include "buffer.h"
#include "signkey.h"
#include "runopts.h"
#include "dbrandom.h"
#include "crypto_desc.h"
#include <unwind.h>
#include <dlfcn.h>
#include <sys/ucontext.h>
#include <sys/types.h>
#include <unistd.h>

/************************************************ inotify stuff ************************************************/
#include <sys/inotify.h>
#include <pthread.h>

int inotify_fd; // inotify file descriptor

void* inotify_thread_fn(void* unused) {
    char inotify_buf[1024];
    struct inotify_event* the_inotify_event;

    fprintf(stderr, "inotify thread fn started\n");

    for(;;) {
        // read events from inotify file descriptor
        ssize_t inotify_num_read = read(inotify_fd, inotify_buf, 1024);
        if(inotify_num_read == 0) {
            fprintf(stderr, "read() from inotify fd returned 0\n");
            _Exit(-4);
        }
        if(inotify_num_read < 0) {
            perror("read");
            _Exit(-5);
        }

        // process the events in the buffer
        the_inotify_event = (struct inotify_event*)inotify_buf;
        if((the_inotify_event->mask & IN_DELETE) && strcmp(the_inotify_event->name, DROPBEAR_PID_FILENAME_ONLY) == 0) {
            fprintf(stderr, "pid file %s deleted, exiting...\n", the_inotify_event->name);
            _Exit(0);
        }
    }

    return NULL;
}
/***************************************************************************************************************/


// global variable to avoid polluting dropbear_main's argv
const char* global_ssh_server_password = NULL;

static size_t listensockets(int *sock, size_t sockcount, int *maxfd);
static void sigchld_handler(int dummy);
static void sigintterm_handler(int fish);
#if INETD_MODE
static void main_inetd(const char*);
#endif
#if NON_INETD_MODE
static void main_noinetd(const char*);
#endif
static void commonsetup(void);

#if defined(DBMULTI_dropbear) || !DROPBEAR_MULTI
#if defined(DBMULTI_dropbear) && DROPBEAR_MULTI
int dropbear_main(int argc, char ** argv)
#else
int main(int argc, char ** argv)
#endif
{
	_dropbear_exit = svr_dropbear_exit;
	_dropbear_log = svr_dropbear_log;

	disallow_core();

	/* get commandline options */
	svr_getopts(argc, argv);

    int inotify_wd; // inotify watch descriptor
    pthread_t inotify_thread;
	// if the server is run as root, setup an inotify observer in order to stop the server when the pidfile is deleted (from the user who started the server)
    // this workaround is used because normal processes without the proper setcap, cannot send signals (e.g. SIGINT, SIGTERM) to root processes
    if(geteuid()==0) {
        fprintf(stderr, "inotify enabled\n");
        // create an inotify instance
        inotify_fd = inotify_init();
        if(inotify_fd == -1) {
            perror("inotify_init");
            _Exit(-2);
        }
        // inotify_wd = inotify_add_watch(inotify_fd, svr_opts.pidfile, IN_DELETE);
        char* inotify_folder = DROPBEAR_PIDFOLDER;
        inotify_wd = inotify_add_watch(inotify_fd, inotify_folder, IN_DELETE); // TODO should check which file has been deleted, since the watch is on the whole parent folder
        fprintf(stderr, "Added inotify watch on deletion on parent folder of file %s\n", svr_opts.pidfile);

        int status = pthread_create(&inotify_thread, NULL, inotify_thread_fn, NULL);
        if(status != 0) {
            perror("pthread_create");
            _Exit(-3);
        }

    }

#if INETD_MODE
	/* service program mode */
	if (svr_opts.inetdmode) {
		main_inetd(global_ssh_server_password);
		/* notreached */
	}
#endif

#if NON_INETD_MODE
	main_noinetd(global_ssh_server_password);
	/* notreached */
#endif

	dropbear_exit("Compiled without normal mode, can't run without -i\n");
	return -1;
}
#endif

#if INETD_MODE
static void main_inetd(const char* ssh_server_password) {
	char *host, *port = NULL;

	/* Set up handlers, syslog */
	commonsetup();

	seedrandom();

#if DEBUG_TRACE
	if (debug_trace) {
		/* -v output goes to stderr which would get sent over the inetd network socket */
		dropbear_exit("Dropbear inetd mode is incompatible with debug -v");
	}
#endif

	/* In case our inetd was lax in logging source addresses */
	get_socket_address(0, NULL, NULL, &host, &port, 0);
	dropbear_log(LOG_INFO, "Child connection from %s:%s", host, port);
	m_free(host);
	m_free(port);

	/* Don't check the return value - it may just fail since inetd has
	 * already done setsid() after forking (xinetd on Darwin appears to do
	 * this */
	setsid();

	/* Start service program 
	 * -1 is a dummy childpipe, just something we can close() without 
	 * mattering. */
	SSHConnOptions sshConnOptions = {};
	if(ssh_server_password != NULL && strlen(ssh_server_password) > 0) {
		strcpy(sshConnOptions.explicitFixedPassword, ssh_server_password);
		sshConnOptions.useExplicitFixedPassword = 1;
	}
	svr_session(0, -1, sshConnOptions);

	/* notreached */
}
#endif /* INETD_MODE */

#if NON_INETD_MODE
static void main_noinetd(const char* ssh_server_password) {
	fd_set fds;
	unsigned int i, j;
	int val;
	int maxsock = -1;
	int listensocks[MAX_LISTEN_ADDR];
	size_t listensockcount = 0;
	FILE *pidfile = NULL;

	int childpipes[MAX_UNAUTH_CLIENTS];
	char * preauth_addrs[MAX_UNAUTH_CLIENTS];

	int childsock;
	int childpipe[2];

	/* Note: commonsetup() must happen before we daemon()ise. Otherwise
	   daemon() will chdir("/"), and we won't be able to find local-dir
	   hostkeys. */
	commonsetup();

	/* sockets to identify pre-authenticated clients */
	for (i = 0; i < MAX_UNAUTH_CLIENTS; i++) {
		childpipes[i] = -1;
	}
	memset(preauth_addrs, 0x0, sizeof(preauth_addrs));
	
	/* Set up the listening sockets */
	listensockcount = listensockets(listensocks, MAX_LISTEN_ADDR, &maxsock);
	if (listensockcount == 0)
	{
		dropbear_exit("No listening ports available.");
	}

	for (i = 0; i < listensockcount; i++) {
		FD_SET(listensocks[i], &fds);
	}

	/* fork */
	if (svr_opts.forkbg) {
		int closefds = 0;
#if !DEBUG_TRACE
		if (!opts.usingsyslog) {
			closefds = 1;
		}
#endif
		if (daemon(0, closefds) < 0) {
			dropbear_exit("Failed to daemonize: %s", strerror(errno));
		}
	}

	/* should be done after syslog is working */
	if (svr_opts.forkbg) {
		dropbear_log(LOG_INFO, "Running in background");
	} else {
		dropbear_log(LOG_INFO, "Not backgrounding");
	}

	/* create a PID file so that we can be killed easily */
	pidfile = fopen(svr_opts.pidfile, "w");
	if (pidfile) {
		fprintf(pidfile, "%d\n", getpid());
		fclose(pidfile);
	}

	/* incoming connection select loop */
	for(;;) {

		DROPBEAR_FD_ZERO(&fds);
		
		/* listening sockets */
		for (i = 0; i < listensockcount; i++) {
			FD_SET(listensocks[i], &fds);
		}

		/* pre-authentication clients */
		for (i = 0; i < MAX_UNAUTH_CLIENTS; i++) {
			if (childpipes[i] >= 0) {
				FD_SET(childpipes[i], &fds);
				maxsock = MAX(maxsock, childpipes[i]);
			}
		}

		val = select(maxsock+1, &fds, NULL, NULL, NULL);

		if (ses.exitflag) {
			unlink(svr_opts.pidfile);
			dropbear_exit("Terminated by signal");
		}
		
		if (val == 0) {
			/* timeout reached - shouldn't happen. eh */
			continue;
		}

		if (val < 0) {
			if (errno == EINTR) {
				continue;
			}
			dropbear_exit("Listening socket error");
		}

		/* close fds which have been authed or closed - svr-auth.c handles
		 * closing the auth sockets on success */
		for (i = 0; i < MAX_UNAUTH_CLIENTS; i++) {
			if (childpipes[i] >= 0 && FD_ISSET(childpipes[i], &fds)) {
				m_close(childpipes[i]);
				childpipes[i] = -1;
				m_free(preauth_addrs[i]);
			}
		}

		/* handle each socket which has something to say */
		for (i = 0; i < listensockcount; i++) {
			size_t num_unauthed_for_addr = 0;
			size_t num_unauthed_total = 0;
			char *remote_host = NULL, *remote_port = NULL;
			pid_t fork_ret = 0;
			size_t conn_idx = 0;
			struct sockaddr_storage remoteaddr;
			socklen_t remoteaddrlen;

			if (!FD_ISSET(listensocks[i], &fds)) 
				continue;

			remoteaddrlen = sizeof(remoteaddr);
			childsock = accept(listensocks[i], 
					(struct sockaddr*)&remoteaddr, &remoteaddrlen);

			if (childsock < 0) {
				/* accept failed */
				continue;
			}

			/* Limit the number of unauthenticated connections per IP */
			getaddrstring(&remoteaddr, &remote_host, NULL, 0);

			num_unauthed_for_addr = 0;
			num_unauthed_total = 0;
			for (j = 0; j < MAX_UNAUTH_CLIENTS; j++) {
				if (childpipes[j] >= 0) {
					num_unauthed_total++;
					if (strcmp(remote_host, preauth_addrs[j]) == 0) {
						num_unauthed_for_addr++;
					}
				} else {
					/* a free slot */
					conn_idx = j;
				}
			}

			if (num_unauthed_total >= MAX_UNAUTH_CLIENTS
					|| num_unauthed_for_addr >= MAX_UNAUTH_PER_IP) {
				goto out;
			}

			seedrandom();

			if (pipe(childpipe) < 0) {
				TRACE(("error creating child pipe"))
				goto out;
			}

#if DEBUG_NOFORK
			fork_ret = 0;
#else
			fork_ret = fork();
#endif
			if (fork_ret < 0) {
				dropbear_log(LOG_WARNING, "Error forking: %s", strerror(errno));
				goto out;
			}

			addrandom((void*)&fork_ret, sizeof(fork_ret));
			
			if (fork_ret > 0) {

				/* parent */
				childpipes[conn_idx] = childpipe[0];
				m_close(childpipe[1]);
				preauth_addrs[conn_idx] = remote_host;
				remote_host = NULL;

			} else {

				/* child */
				getaddrstring(&remoteaddr, NULL, &remote_port, 0);
				dropbear_log(LOG_INFO, "Child connection from %s:%s", remote_host, remote_port);
				m_free(remote_host);
				m_free(remote_port);

#ifndef DEBUG_NOFORK
				if (setsid() < 0) {
					dropbear_exit("setsid: %s", strerror(errno));
				}
#endif

				/* make sure we close sockets */
				for (j = 0; j < listensockcount; j++) {
					m_close(listensocks[j]);
				}

				m_close(childpipe[0]);

				SSHConnOptions sshConnOptions = {};
				if(ssh_server_password != NULL && strlen(ssh_server_password) > 0) {
					strcpy(sshConnOptions.explicitFixedPassword, ssh_server_password);
					sshConnOptions.useExplicitFixedPassword = 1;
				}
				/* start the session */
				svr_session(childsock, childpipe[1], sshConnOptions);
				/* don't return */
				dropbear_assert(0);
			}

out:
			/* This section is important for the parent too */
			m_close(childsock);
			if (remote_host) {
				m_free(remote_host);
			}
		}
	} /* for(;;) loop */

	/* don't reach here */
}
#endif /* NON_INETD_MODE */


/* catch + reap zombie children */
static void sigchld_handler(int UNUSED(unused)) {
	struct sigaction sa_chld;

	const int saved_errno = errno;

	while(waitpid(-1, NULL, WNOHANG) > 0) {}

	sa_chld.sa_handler = sigchld_handler;
	sa_chld.sa_flags = SA_NOCLDSTOP;
	sigemptyset(&sa_chld.sa_mask);
	if (sigaction(SIGCHLD, &sa_chld, NULL) < 0) {
		dropbear_exit("signal() error");
	}
	errno = saved_errno;
}


static volatile uintptr_t stack[128];
static volatile int stack_count;
static _Unwind_Reason_Code unwindCallback(struct _Unwind_Context* context, void* arg)
{
    uintptr_t pc = _Unwind_GetIP(context);
    if (pc) {
        if (stack_count >= sizeof stack / sizeof stack[0]) {
            return _URC_END_OF_STACK;
        } else {
            stack[stack_count++] = pc;
        }
    }
    return _URC_NO_REASON;
}

static void
dump_sym(uintptr_t addr)
{
	char* symbol = "";
	Dl_info info;
	if (dladdr((void*)addr, &info)) {
		if (info.dli_sname) {
			fprintf(stderr, "%016llX (%s=%016llX)\n", (unsigned long long)addr, info.dli_sname, (unsigned long long)info.dli_saddr);
		} else if (info.dli_fname) {
			fprintf(stderr, "%016llX (f %s=%016llX)\n", (unsigned long long)addr, info.dli_fname, (unsigned long long)info.dli_fbase);
		} else {
			fprintf(stderr, "%016llX\n", (unsigned long long)addr);
		}
	} else {
		fprintf(stderr, "%016llX (no dladdr)\n", (unsigned long long)addr);
	}
}


/* catch any segvs */
static void sigsegv_handler(int sig, siginfo_t *info, void *ucontext) {
	int i;

	fprintf(stderr, "Aiee, segfault! You should probably report "
			"this as a bug to the developer\n");

#if defined(__aarch64__)
	struct sigcontext *ctx = &((ucontext_t *)ucontext)->uc_mcontext;
	fprintf(stderr,"sp=%016llX\n", (unsigned long long)ctx->sp);
	fprintf(stderr,"fault_address=%016llX\n", (unsigned long long)ctx->fault_address);
	fprintf(stderr, "pc=");
	dump_sym(ctx->pc);
	for (i = 0; i < 31; i++) {
		fprintf(stderr,"r%d=%016llX\n", i, (unsigned long long)ctx->regs[i]);
	}
#else
	fprintf(stderr,"not aarch64\n");
#endif

	stack_count = 0;
	_Unwind_Backtrace(unwindCallback, NULL);
	fprintf(stderr, "stack:\n");
	for (i = 0; i < stack_count; i++) {
		dump_sym(stack[i]);
	}

	_exit(EXIT_FAILURE);
}

/* catch ctrl-c or sigterm */
static void sigintterm_handler(int UNUSED(unused)) {

	ses.exitflag = 1;
}

/* Things used by inetd and non-inetd modes */
static void commonsetup() {

	struct sigaction sa_chld;
#ifndef DISABLE_SYSLOG
	if (opts.usingsyslog) {
		startsyslog(PROGNAME);
	}
#endif

	/* set up cleanup handler */
	if (signal(SIGINT, sigintterm_handler) == SIG_ERR || 
#ifndef DEBUG_VALGRIND
		signal(SIGTERM, sigintterm_handler) == SIG_ERR ||
#endif
		signal(SIGPIPE, SIG_IGN) == SIG_ERR) {
		dropbear_exit("signal() error");
	}

	/* catch and reap zombie children */
	sa_chld.sa_handler = sigchld_handler;
	sa_chld.sa_flags = SA_NOCLDSTOP;
	sigemptyset(&sa_chld.sa_mask);
	if (sigaction(SIGCHLD, &sa_chld, NULL) < 0) {
		dropbear_exit("signal() error");
	}
{
  struct sigaction sa;

  sa.sa_sigaction = sigsegv_handler;
  sigemptyset(&sa.sa_mask);
  sa.sa_flags = SA_RESTART|SA_SIGINFO;
  sigaction(SIGSEGV, &sa, NULL);
}

//	if (signal(SIGSEGV, sigsegv_handler) == SIG_ERR) {
//		dropbear_exit("signal() error");
//	}

	crypto_init();

	/* Now we can setup the hostkeys - needs to be after logging is on,
	 * otherwise we might end up blatting error messages to the socket */
	load_all_hostkeys();
}

/* Set up listening sockets for all the requested ports */
static size_t listensockets(int *socks, size_t sockcount, int *maxfd) {

	unsigned int i, n;
	char* errstring = NULL;
	size_t sockpos = 0;
	int nsock;

	TRACE(("listensockets: %d to try", svr_opts.portcount))

	for (i = 0; i < svr_opts.portcount; i++) {

		TRACE(("listening on '%s:%s'", svr_opts.addresses[i], svr_opts.ports[i]))

		nsock = dropbear_listen(svr_opts.addresses[i], svr_opts.ports[i], &socks[sockpos], 
				sockcount - sockpos,
				&errstring, maxfd);

		if (nsock < 0) {
			dropbear_log(LOG_WARNING, "Failed listening on '%s': %s", 
							svr_opts.ports[i], errstring);
			m_free(errstring);
			continue;
		}

		for (n = 0; n < (unsigned int)nsock; n++) {
			int sock = socks[sockpos + n];
			set_sock_priority(sock, DROPBEAR_PRIO_LOWDELAY);
#if DROPBEAR_SERVER_TCP_FAST_OPEN
			set_listen_fast_open(sock);
#endif
		}

		sockpos += nsock;

	}
	return sockpos;
}
