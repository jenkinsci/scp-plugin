package be.certipost.hudson.plugin;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.console.AnnotatedLargeText;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Result;
import hudson.model.Hudson.MasterComputer;
import hudson.slaves.EnvironmentVariablesNodeProperty;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.CopyOnWriteList;
import hudson.util.DescribableList;
import hudson.util.FormValidation;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.PrintStream;
import java.lang.Runnable;
import java.lang.Thread;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * 
 * @author Ramil Israfilov
 * 
 */
public final class SCPRepositoryPublisher extends Notifier {

	/**
	 * Name of the scp site to post a file to.
	 */
	private String siteName;
	public static final Logger LOGGER = Logger
			.getLogger(SCPRepositoryPublisher.class.getName());

	private final List<Entry> entries;

    @DataBoundConstructor
    public SCPRepositoryPublisher(String siteName, List<Entry> entries) {
        if (siteName == null) {
            // defaults to the first one
            SCPSite[] sites = DESCRIPTOR.getSites();
			if (sites.length > 0) {
                siteName = sites[0].getName();
            }
        }
        this.entries = entries;
        this.siteName = siteName;
    }

	public List<Entry> getEntries() {
		return entries;
	}

	public SCPSite getSite() {
		SCPSite[] sites = DESCRIPTOR.getSites();
		if (siteName == null && sites.length > 0)
			// default
			return sites[0];

		for (SCPSite site : sites) {
			if (site.getName().equals(siteName))
				return site;
		}
		return null;
	}

	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.BUILD;
	}

	/**
	 * Returns the environment variables set for a node/slave. So you can use
	 * them, as are in your environment
	 * 
	 * @param envVars
	 * @return
	 */
	public static EnvVars getEnvVars() {
		DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties = getNodeProperties();

		// System.out.println(".getEnvVars()Computer.currentComputer() = "+Computer.currentComputer()+
		// "nodeProperties = "+nodeProperties);

		Iterator<NodeProperty<?>> iterator = nodeProperties.iterator();
		while (iterator.hasNext()) {
			NodeProperty<?> next = iterator.next();
			// System.out.println(".getEnvVars()Computer.currentComputer() = "+Computer.currentComputer()+" next = "
			// + next);
			if (next instanceof EnvironmentVariablesNodeProperty) {
				EnvironmentVariablesNodeProperty envVarProp = (EnvironmentVariablesNodeProperty) next;
				EnvVars envVars = envVarProp.getEnvVars();
				return envVars;
			}

		}
		return null;
	}

	private static DescribableList<NodeProperty<?>, NodePropertyDescriptor> getNodeProperties() {
		Node node = Computer.currentComputer().getNode();
		DescribableList<NodeProperty<?>, NodePropertyDescriptor> nodeProperties = node
				.getNodeProperties();

		if (Computer.currentComputer() instanceof MasterComputer) {
			Hudson instance = Hudson.getInstance();
			nodeProperties = instance.getGlobalNodeProperties();
		}
		return nodeProperties;
	}

	@Override
	public boolean perform(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener) throws InterruptedException, IOException {
		final Result cachedResult;
		cachedResult = build.getResult();
		SCPSite scpsite = null;
		PrintStream logger = listener.getLogger();
		Session session = null;
		ChannelSftp channel = null;
		boolean delaySessionClose = false;
		try {
			scpsite = getSite();
			if (scpsite == null) {
				log(logger,
						"No SCP site is configured. This is likely a configuration problem.");
				build.setResult(Result.UNSTABLE);
				return true;
			}
			log(logger, "Connecting to " + scpsite.getHostname());
			session = scpsite.createSession(logger);
			channel = scpsite.createChannel(logger, session);

			Map<String, String> envVars = build.getEnvironment(listener);
			// Patched for env vars
			EnvVars objNodeEnvVars = getEnvVars();
			if (objNodeEnvVars != null) {
				envVars.putAll(objNodeEnvVars);
			}
			// ~ Patched for env vars

			for (Entry e : entries) {
				if (!e.copyAfterFailure && cachedResult == Result.FAILURE) {
					// build failed. don't post this file.
					continue;
				}

				String expanded = Util.replaceMacro(e.sourceFile, envVars);
				FilePath ws = build.getWorkspace();
				FilePath[] src = ws.list(expanded);

				String folderPath = Util.replaceMacro(e.filePath, envVars);

				// Fix for recursive mkdirs
				folderPath = folderPath.trim();

				if (e.copyConsoleLog) {
					final OutputStream out;
					final BufferedWriter writer;
					final Thread consoleWriterThread;
					final Session consoleSession;
					final ChannelSftp consoleChannel;
					if (entries.size() > 1) {
						// If we are copying more than the console log
						// we need a separate connection for the console
						// log due to threading/lock issues.
						consoleSession = scpsite.createSession(null);
						consoleChannel = scpsite.createChannel(null, consoleSession);
					}
					else {
						// Otherwise use the existing connection.
						consoleSession = session;
						consoleChannel = channel;
						delaySessionClose = true;
					}

					out = scpsite.createOutStream(folderPath,
						"console.html", logger, consoleChannel);
					writer = new BufferedWriter(new OutputStreamWriter(out));
					consoleWriterThread = new Thread(new consoleRunnable(
						build, scpsite, consoleSession, consoleChannel, writer));
					log(logger, "Copying console log.");
					consoleWriterThread.start();

					continue;
				}
				if (src.length == 0) {
					// try to do error diagnostics
					log(logger, ("No file(s) found: " + expanded));
					String error = ws.validateAntFileMask(expanded);
					if (error != null)
						log(logger, error);
					continue;
				}

				// Making workspace to have the same path separators like in the
				// FilePath objects
				String strWorkspacePath = ws.toString();

				String strFirstFile = src[0].toString();
				if (strFirstFile.indexOf('\\') >= 0) {
					strWorkspacePath = strWorkspacePath.replace('/', '\\');
				} else {
					strWorkspacePath = strWorkspacePath.replace('\\', '/');// Linux
					// Unix
				}

				// System.out
				// .println("SCPRepositoryPublisher.perform()strWorkspacePath = "
				// + strWorkspacePath);
				envVars.put("strWorkspacePath", strWorkspacePath);
				// ~Fix for recursive mkdirs

				if (src.length == 1) {
					// log(logger, "remote folderPath '" + folderPath
					// + "',local file:'" + src[0].getName() + "'");
					// System.out.println("remote folderPath '" + folderPath
					// + "',local file:'" + src[0].getName() + "'");
					scpsite.upload(folderPath, src[0], e.keepHierarchy, envVars, logger, channel);
				} else {
					for (FilePath s : src) {
						// System.out.println("remote folderPath '" + folderPath
						// + "',local file:'" + s.getName() + "'");
						// log(logger, "remote folderPath '" + folderPath
						// + "',local file:'" + s.getName() + "'");
						scpsite.upload(folderPath, s, e.keepHierarchy, envVars, logger, channel);
					}
				}
			}
		} catch (IOException e) {
			e.printStackTrace(listener.error("Failed to upload files"));
			build.setResult(Result.UNSTABLE);
		} catch (JSchException e) {
			e.printStackTrace(listener.error("Failed to upload files"));
			build.setResult(Result.UNSTABLE);
		} catch (SftpException e) {
			e.printStackTrace(listener.error("Failed to upload files"));
			build.setResult(Result.UNSTABLE);
		} finally {
			if (scpsite != null && !delaySessionClose) {
				scpsite.closeSession(logger, session, channel);
			}

		}

		return true;
	}

	@Override
	public BuildStepDescriptor<Publisher> getDescriptor() {
		return DESCRIPTOR;
	}

	@Extension
	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();

	public static final class DescriptorImpl extends
			BuildStepDescriptor<Publisher> {

		public DescriptorImpl() {
			super(SCPRepositoryPublisher.class);
			load();
		}

		protected DescriptorImpl(Class<? extends Publisher> clazz) {
			super(clazz);
		}

		private final CopyOnWriteList<SCPSite> sites = new CopyOnWriteList<SCPSite>();

		public String getDisplayName() {
			return Messages.SCPRepositoryPublisher_DisplayName();
		}

		public String getShortName() {
			return "[SCP] ";
		}

		@Override
		public String getHelpFile() {
			return "/plugin/scp/help.html";
		}

		@Override
		public boolean isApplicable(Class<? extends AbstractProject> jobType) {
			return true;
		}

		@Override
        public Publisher newInstance(StaplerRequest req, JSONObject formData) {
            return req.bindJSON(SCPRepositoryPublisher.class, formData);
        }

		public SCPSite[] getSites() {
			Iterator<SCPSite> it = sites.iterator();
			int size = 0;
			while (it.hasNext()) {
				it.next();
				size++;
			}
			return sites.toArray(new SCPSite[size]);
		}

		@Override
		public boolean configure(StaplerRequest req, JSONObject formData) {
			sites.replaceBy(req.bindParametersToList(SCPSite.class, "scp."));
			save();
			return true;
		}

		public FormValidation doKeyfileCheck(@QueryParameter String keyfile) {
			keyfile = Util.fixEmpty(keyfile);
			if (keyfile != null) {
				File f = new File(keyfile);
				if (!f.isFile()) {
					return FormValidation.error(Messages.SCPRepositoryPublisher_KeyFileNotExist());
				}
			}

			return FormValidation.ok();
		}

		public FormValidation doLoginCheck(StaplerRequest request) {
			String hostname = Util.fixEmpty(request.getParameter("hostname"));
			if (hostname == null) {// hosts is not entered yet
				return FormValidation.ok();
			}
			SCPSite site = new SCPSite(hostname, request.getParameter("port"),
					request.getParameter("user"), request.getParameter("pass"),
					request.getParameter("keyfile"));
			try {
				try {
					Session session = site.createSession(new PrintStream(
							System.out));
					site.closeSession(new PrintStream(System.out), session,
							null);
				} catch (JSchException e) {
					LOGGER.log(Level.SEVERE, e.getMessage());
					throw new IOException(Messages.SCPRepositoryPublisher_NotConnect());
				}
			} catch (IOException e) {
				LOGGER.log(Level.SEVERE, e.getMessage());
				return FormValidation.error(e.getMessage());
			}
			return FormValidation.ok();
		}

	}

	public String getSiteName() {
		return siteName;
	}

	public void setSiteName(String siteName) {
		this.siteName = siteName;
	};

	protected void log(final PrintStream logger, final String message) {
		logger.println(StringUtils.defaultString(DESCRIPTOR.getShortName())
				+ message);
	}

	private class consoleRunnable implements Runnable {
		private final AbstractBuild build;
		private final SCPSite scpsite;
		private final Session session;
		private final ChannelSftp channel;
		private final BufferedWriter writer;

		consoleRunnable(AbstractBuild build, SCPSite scpsite, Session session,
				ChannelSftp channel, BufferedWriter writer) {
			this.build = build;
			this.scpsite = scpsite;
			this.session = session;
			this.channel = channel;
			this.writer = writer;
		}

		public void run () {
			AnnotatedLargeText logText;
			final StringWriter strWriter;
			strWriter = new StringWriter();
			long pos = 0;

			try {
				strWriter.write("<pre>\n");
				do {
					logText = build.getLogText();
					// Use strWriter as temp storage because
					// writeHTMLTo closes the stream.
					pos = logText.writeHtmlTo(pos, strWriter);
					writer.write(strWriter.toString());
					strWriter.getBuffer().setLength(0);
					if(!logText.isComplete()) {
						// Yield to other threads while we wait
						// for more data.
						try {
							Thread.sleep(500);
						} catch (InterruptedException e) {
							// Ignore and try reading again.
						}
					}
				} while(!logText.isComplete());
				writer.write("</pre>\n");

				writer.flush();
				writer.close();
			} catch (IOException e) {
				//Ignore the error not much we can do about it.
			} finally {
				scpsite.closeSession(null, session, channel);
			}
		}
	}
}
