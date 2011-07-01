package be.certipost.hudson.plugin;

import hudson.EnvVars;
import hudson.Extension;
import hudson.Launcher;
import hudson.Util;
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

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * 
 * @author Ramil Israfilov
 * 
 */
public final class SCPRepositoryPublisher extends Notifier {

	public static final Logger LOGGER = Logger
		.getLogger(SCPRepositoryPublisher.class.getName());

	/**
	 * The list of individual site publishers
	 */
	private List<SitePublisher> publishers;

	/**
	 * Name of the scp site to post a file to.
	 * Only used for import of existing configurations.
	 */
	@Deprecated
	private String siteName;

	/**
	 * The list of entries for the site.
	 * Only used for import of existing configurations.
	 */
	@Deprecated
	private List<Entry> entries;

    @DataBoundConstructor
    public SCPRepositoryPublisher(List<SitePublisher> publishers) {
    	if (publishers == null) {
    		this.publishers = new ArrayList<SitePublisher>();
    	} else {
    		this.publishers = publishers;
    	}
    	this.siteName = null;
    	this.entries = null;
    }

    /**
     * @return the publishers for this site
     */
    public List<SitePublisher> getPublishers() {
    	return publishers;
    }

    public Object readResolve() {
    	if ((siteName != null) || (entries != null)) {
    		// Convert from old style single site to new multi-site
    		// configuration.
    		if (this.publishers == null) {
    			this.publishers = new ArrayList<SitePublisher>();
    		}
    		this.publishers.add(new SitePublisher(this.siteName, this.entries));
    		this.siteName = null;
    		this.entries = null;
    	}
    	return this;
    }

    @Deprecated
	public List<Entry> getEntries() {
		return entries;
	}

    @Deprecated
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

		if (build.getResult() == Result.FAILURE) {
			// build failed. don't post
			return true;
		}

		PrintStream logger = listener.getLogger();

		try {
			Map<String, String> envVars = build.getEnvironment(listener);
			for (SitePublisher publisher : publishers) {
				publisher.performUpload(build, launcher, listener, logger, envVars);
			}
		} catch (IOException e) {
			e.printStackTrace(listener.error("Failed to upload files"));
			build.setResult(Result.UNSTABLE);
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
			SCPSite site = new SCPSite("", hostname, request.getParameter("port"),
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

    @Deprecated
	public String getSiteName() {
		return siteName;
	}

    @Deprecated
	public void setSiteName(String siteName) {
		this.siteName = siteName;
	};

	protected static void log(final PrintStream logger, final String message) {
		logger.println(StringUtils.defaultString(DESCRIPTOR.getShortName())
				+ message);
	}
}
