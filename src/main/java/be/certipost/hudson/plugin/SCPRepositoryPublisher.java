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

import hudson.util.IOException2;
import hudson.util.ListBoxModel;
import hudson.util.ListBoxModel.Option;
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
        return BuildStepMonitor.NONE;
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

        Iterator<NodeProperty<?>> iterator = nodeProperties.iterator();
        while (iterator.hasNext()) {
            NodeProperty<?> next = iterator.next();
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

        try {
            scpsite = getSite();
            if (scpsite == null) {
                log(logger,
                        "No SCP site is configured. This is likely a configuration problem.");
                build.setResult(Result.UNSTABLE);
                return true;
            }

            Map<String, String> envVars = build.getEnvironment(listener);
            // Patched for env vars
            EnvVars objNodeEnvVars = getEnvVars();
            if (objNodeEnvVars != null) {
                envVars.putAll(objNodeEnvVars);
            }
            // ~ Patched for env vars

            // check whether scp is configured to copy console log
            boolean copyLog = false;
            for (Entry e : entries) {
                if (e.copyConsoleLog) {
                    copyLog = true;
                    break;
                }
            }
            // create this session only if scp is configured to upload more than just
            // the console log.  the console log upload requires a seperate session
            if (!(entries.size() == 1 && copyLog)) {
                log(logger, "Connecting to " + scpsite.getHostname());
                session = scpsite.createSession(logger);
                channel = scpsite.createChannel(logger, session);
            }

            for (Entry e : entries) {
                if (!e.copyAfterFailure && cachedResult == Result.FAILURE) {
                    // build failed. don't post this file.
                    continue;
                }

                String folderPath = Util.replaceMacro(e.filePath, envVars);
                // Fix for recursive mkdirs
                folderPath = folderPath.trim();

                if (e.copyConsoleLog) {
                    // copy console log
                    final Thread consoleWriterThread;
                    consoleWriterThread = new Thread(new consoleRunnable(build, scpsite, folderPath, logger));
                    log(logger, "Copying console log.");
                    consoleWriterThread.start();

                    continue;
                }
                // copy files other than console log
                String expanded = Util.replaceMacro(e.sourceFile, envVars);
                FilePath ws = build.getWorkspace();
                if (ws == null) {
                    log(logger, "No workspace found, files cannot be copied. " +
                        "Probably an error communicating with slave.");
                    continue;
                }

                FilePath[] src = ws.list(expanded);
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
                    strWorkspacePath = strWorkspacePath.replace('\\', '/');
                    // Unix
                }

                envVars.put("strWorkspacePath", strWorkspacePath);
                // ~Fix for recursive mkdirs

                if (src.length == 1) {
                    scpsite.upload(folderPath, src[0], e.keepHierarchy, envVars, logger, channel);
                } else {
                    for (FilePath s : src) {
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
            if (scpsite != null) {
                scpsite.closeSession(logger, session, channel);
            }
        }

        return true;
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

        @Override
        public String getDisplayName() {
            return Messages.SCPRepositoryPublisher_DisplayName();
        }

        public String getShortName() {
            return "[SCP] ";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
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
            sites.replaceBy(req.bindJSONToList(SCPSite.class,
                                               formData.get("sites")));
            save();
            return true;
        }

        public ListBoxModel doFillSiteNameItems() {
            ListBoxModel model = new ListBoxModel();
            for (SCPSite site : getSites()) {
                model.add(site.getName());
            }
            return model;
        }
    }

    public String getSiteName() {
        return siteName;
    }

    public void setSiteName(String siteName) {
        this.siteName = siteName;
    }

    protected void log(final PrintStream logger, final String message) {
        logger.println(StringUtils.defaultString(DESCRIPTOR.getShortName())
                + message);
    }

    private class consoleRunnable implements Runnable {
        private final AbstractBuild build;
        private final SCPSite scpsite;
        private final String path;
        private final PrintStream logger;

        consoleRunnable(AbstractBuild build, SCPSite scpsite, String path, PrintStream logger) {
            this.build = build;
            this.scpsite = scpsite;
            this.path = path;
            this.logger = logger;
        }

        public void run () {
            AnnotatedLargeText logText;
            final StringWriter strWriter;
            strWriter = new StringWriter();
            long pos = 0;
            Session session = null;
            ChannelSftp channel = null;
            OutputStream out = null;
            BufferedWriter writer = null;

            try {
                strWriter.write("<pre>\n");
                session = scpsite.createSession(logger);
                channel = scpsite.createChannel(logger, session);
                out = scpsite.createOutStream(path, "console.html", logger, channel);
                writer = new BufferedWriter(new OutputStreamWriter(out));

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
                LOGGER.info("Failed to upload console log: " + e.getMessage());
            } catch (JSchException e) {
                LOGGER.info("Failed to upload console log: " + e.getMessage());
            } catch (SftpException e) {
                LOGGER.info("Failed to upload console log: " + e.getMessage());
            } finally {
                if (scpsite != null) {
                    scpsite.closeSession(null, session, channel);
                }
            }
        }
    }
}
