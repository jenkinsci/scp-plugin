package be.certipost.hudson.plugin;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsMatcher;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.domains.HostnamePortRequirement;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.google.common.base.Strings;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.Node;
import hudson.model.Hudson.MasterComputer;
import hudson.security.ACL;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import org.apache.commons.lang.StringUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 *
 * @author Ramil Israfilov
 *
 */
public class CredentialsSCPSite extends AbstractDescribableImpl<CredentialsSCPSite> {

    public static class LegacySCPSite extends CredentialsSCPSite {
        String password;
        String keyfile;
    }

    String displayname;
    String hostname;
    String credentialsId;
    StandardCredentials user;
    String username;
    int port;
    String rootRepositoryPath;

    public static final List<DomainRequirement> NO_REQUIREMENTS = Collections.<DomainRequirement> emptyList();

    public static final Logger LOGGER = Logger.getLogger(CredentialsSCPSite.class
            .getName());

    private CredentialsSCPSite() {
    }

    @DataBoundConstructor
    public CredentialsSCPSite(String displayname, String hostname, String port, String credentialsId,
                   String rootRepositoryPath) {
        this.displayname = displayname;
        this.hostname = hostname;
        try {
            this.port = Integer.parseInt(port);
        } catch (Exception e) {
            this.port = 22;
        }
        this.credentialsId = credentialsId;
        if (rootRepositoryPath != null) {
            this.rootRepositoryPath = rootRepositoryPath.trim();
        }
        setUser(credentialsId);
    }

    public String getDisplayname() {
        return displayname;
    }

    public void setDisplayname(String displayname) {
        this.displayname = displayname;
    }

    public String getHostname() {
        return hostname;
    }

    public void setHostname(String hostname) {
        this.hostname = hostname;
    }

    public StandardCredentials getUser() {
        return user;
    }

    public void setUser(String credentialsId) {
        StandardCredentials user = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StandardCredentials.class, (Item) null, ACL.SYSTEM, NO_REQUIREMENTS),
                CredentialsMatchers.allOf(CredentialsMatchers.withId(credentialsId),
                        CredentialsMatchers.anyOf(CredentialsMatchers.instanceOf(StandardCredentials.class),
                                CredentialsMatchers.instanceOf(SSHUserPrivateKey.class))));
        this.user = user;
    }

    public String getPort() {
        return "" + port;
    }

    public void setPort(String port) {
        try {
            this.port = Integer.parseInt(port);
        } catch (Exception e) {
            this.port = 22;
        }
    }

    public int getIntegerPort() {
        return port;
    }

    @SuppressWarnings("unused") // by stapler
    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRootRepositoryPath() {
        return rootRepositoryPath;
    }

    public void setRootRepositoryPath(String rootRepositoryPath) {
        this.rootRepositoryPath = rootRepositoryPath.trim();
    }

    public String getName() {
        if (StringUtils.isEmpty(displayname)) {
            return username + "@" + hostname + ":" + rootRepositoryPath;
        } else {
            return displayname;
        }
    }

    public Session createSession(PrintStream logger) throws JSchException {
        JSch jsch = new JSch();
        Session session = null;

        StandardCredentials user = getUser();

        try {
            if (user == null) {
                String message = "Credentials with id '" + credentialsId + "', no longer exist!";
                throw new InterruptedException(message);
            }

            if (user instanceof SSHUserPrivateKey) {
                LOGGER.log(Level.FINER, "SSHUserPrivateKey used through credentialID {0}", credentialsId);
                SSHUserPrivateKey userSsh = (SSHUserPrivateKey) user;
                jsch.addIdentity(userSsh.getUsername(), userSsh.getPrivateKey().getBytes("UTF-8"), null, null);
                session = jsch.getSession(userSsh.getUsername(), hostname, port);
            } else if (user instanceof StandardUsernamePasswordCredentials) {
                LOGGER.log(Level.FINER, "StandardUsernamePasswordCredentials used through credentialID {0}", credentialsId);
                StandardUsernamePasswordCredentials userNamePassword = (StandardUsernamePasswordCredentials) user;
                session = jsch.getSession(userNamePassword.getUsername(), hostname, port);
                session.setPassword(userNamePassword.getPassword().getPlainText());
                UserInfo ui = new SCPUserInfo(userNamePassword.getPassword().getPlainText());
                session.setUserInfo(ui);
            }

            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            session.setConfig(config);
            session.connect();
        } catch (UnsupportedEncodingException e) {
            LOGGER.log(Level.WARNING, String.format("There was a problem getting your SSH private key"), e);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        return session;
    }

    public ChannelSftp createChannel(PrintStream logger, Session session)
            throws JSchException {
        ChannelSftp channel = (ChannelSftp) session.openChannel("sftp");
        channel.setOutputStream(System.out);
        channel.connect();
        return channel;
    }

    public void closeSession(PrintStream logger, Session session,
                             ChannelSftp channel) {
        if (channel != null) {
            channel.disconnect();
            channel = null;
        }
        if (session != null) {
            session.disconnect();
            session = null;
        }

    }

    public OutputStream createOutStream(String folderPath, String fileName, PrintStream logger, ChannelSftp channel)
            throws IOException, SftpException {
        if (channel == null) {
            throw new IOException("Connection to " + hostname + ", user="
                    + username + " is not established");
        }

        SftpATTRS rootdirstat = channel.stat(rootRepositoryPath);
        if (rootdirstat == null) {
            throw new IOException(
                    "Can't get stat of root repository directory:"
                            + rootRepositoryPath);
        } else {
            if (!rootdirstat.isDir()) {
                throw new IOException(rootRepositoryPath
                        + " is not a directory");
            }
        }

        mkdirs(folderPath, logger, channel);
        String strTmp = concatDir(folderPath, fileName);
        String strNewFilename = concatDir(rootRepositoryPath, strTmp);
        return channel.put(strNewFilename);
    }

    public void upload(String folderPath, FilePath filePath, boolean keepHierarchy,
                       Map<String, String> envVars, PrintStream logger, ChannelSftp channel)
            throws IOException, InterruptedException, SftpException {
        // if ( session == null ||
        if (channel == null) {
            throw new IOException("Connection to " + hostname + ", user="
                    + username + " is not established");
        }
        SftpATTRS rootdirstat = channel.stat(rootRepositoryPath);
        if (rootdirstat == null) {
            throw new IOException(
                    "Can't get stat of root repository directory:"
                            + rootRepositoryPath);
        } else {
            if (!rootdirstat.isDir()) {
                throw new IOException(rootRepositoryPath
                        + " is not a directory");
            }
        }
        if (filePath.isDirectory()) {
            FilePath[] subfiles = filePath.list("**/*");
            if (subfiles != null) {
                for (int i = 0; i < subfiles.length; i++) {
                    upload(folderPath + "/" + filePath.getName(), subfiles[i],
                            keepHierarchy, envVars, logger, channel);
                }
            }
        } else {
            String localfilename = filePath.getName();
            mkdirs(folderPath, logger, channel);

            String strNewFilename;
            if (keepHierarchy) {
                // Fix for mkdirs
                String strWorkspacePath = envVars.get("strWorkspacePath");
                String strRelativePath = extractRelativePath(strWorkspacePath,
                        filePath, logger);

                String strTmp = concatDir(folderPath, strRelativePath);
                String strNewPath = concatDir(rootRepositoryPath, strTmp);
                if (!strRelativePath.equals("")) {
                    // Make subdirs
                    mkdirs(strTmp, logger, channel);
                }

                if (!strNewPath.endsWith("/")) {
                    strNewPath += "/";
                }

                strNewFilename = strNewPath + localfilename;
            } else {
                String strTmp = concatDir(folderPath, localfilename);
                strNewFilename = concatDir(rootRepositoryPath, strTmp);
            }

            log(logger, "uploading file: '" + strNewFilename + "'");
            InputStream in = filePath.read();
            channel.put(in, strNewFilename);
            // ~Fix for mkdirs

            in.close();
        }

    }

    private void mkdirs(String filePath, PrintStream logger, ChannelSftp channel)
            throws SftpException, IOException {
        String pathnames[] = filePath.split("/");
        String curdir = rootRepositoryPath;
        if (pathnames != null) {
            for (int i = 0; i < pathnames.length; i++) {
                if (pathnames[i].length() == 0) {
                    continue;
                }

                SftpATTRS dirstat = null;
                try {
                    dirstat = channel.stat(curdir + "/" + pathnames[i]);
                } catch (SftpException e) {

                    if (e.getMessage() != null
                            && e.getMessage().indexOf("No such file") == -1) {
                        log(logger, "Error getting stat of  directory:"
                                + curdir + "/" + pathnames[i] + ":"
                                + e.getMessage());
                        throw e;
                    }
                }
                if (dirstat == null) {
                    // try to create dir
                    log(logger, "Trying to create " + curdir + "/"
                            + pathnames[i]);
                    try {
                        channel.mkdir(curdir + "/" + pathnames[i]);
                    } catch (SftpException e) {
                        // The dir may have been created by another scp
                        // connection. If the mkdir fails check if the dir
                        // exists. If it does not exist stat should throw an
                        // exception.
                    }
                    dirstat = channel.stat(curdir + "/" + pathnames[i]);
                }
                if (!dirstat.isDir()) {
                    throw new IOException(curdir + "/" + pathnames[i]
                            + " is not a directory:" + dirstat);
                }
                curdir = curdir + "/" + pathnames[i];
            }
        }
    }

    protected void log(final PrintStream logger, final String message) {
        logger
                .println(StringUtils
                        .defaultString(SCPRepositoryPublisher.DESCRIPTOR
                                .getShortName())
                        + message);
    }

    /**
     * @param folderPath
     * @param strRelativePath
     * @return
     */
    private String concatDir(String folderPath, String strRelativePath) {
        String strTmp;
        if (folderPath.endsWith("/") || folderPath.equals("")) {
            strTmp = folderPath + strRelativePath;
        } else {
            strTmp = folderPath + "/" + strRelativePath;
        }

        return strTmp;
    }

    /**
     * Returns the relative path to the workspace
     *
     * @param strWorkspacePath
     * @param filePath
     * @return
     */
    private String extractRelativePath(String strWorkspacePath,
                                       FilePath filePath, PrintStream logger) {
        String strRet = "";
        String strFilePath = filePath.getParent().toString();
        if (strWorkspacePath.length() == strFilePath.length()) {
            return "";
        }

        if (strFilePath.length() > strWorkspacePath.length()) {
            strRet = strFilePath.substring(strWorkspacePath.length() + 1,
                    strFilePath.length());// Exclude
            // first
            // file
            // separator
        }
        strRet = strRet.replace('\\', '/');

        return strRet;
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

    public static CredentialsSCPSite migrateToCredentials(CredentialsSCPSite site) throws InterruptedException,
            IOException {
        if (!(site instanceof LegacySCPSite)) {
            return site;
        }

        final LegacySCPSite legacy = (LegacySCPSite) site;

        final List<StandardUsernameCredentials> credentialsForDomain = CredentialsProvider.lookupCredentials(
                StandardUsernameCredentials.class, (Item) null, ACL.SYSTEM, new HostnamePortRequirement(site.hostname,
                        site.port));
        final StandardUsernameCredentials existingCredentials = CredentialsMatchers.firstOrNull(credentialsForDomain,
                CredentialsMatchers.withUsername(legacy.username));

        final String credentialId;
        if (existingCredentials == null) {
            String createdCredentialId = UUID.randomUUID().toString();

            final StandardUsernameCredentials credentialsToCreate;
            if (!Strings.isNullOrEmpty(legacy.password)) {
                credentialsToCreate = new UsernamePasswordCredentialsImpl(CredentialsScope.SYSTEM, createdCredentialId,
                        "migrated from previous scp-plugin version", legacy.username, legacy.password);
            } else if (!Strings.isNullOrEmpty(legacy.keyfile)) {
                credentialsToCreate = new BasicSSHUserPrivateKey(CredentialsScope.SYSTEM, createdCredentialId,
                        legacy.username, new BasicSSHUserPrivateKey.FileOnMasterPrivateKeySource(legacy.keyfile), null,
                        "migrated from previous scp-plugin version");
            } else {
                throw new InterruptedException(
                        "Did not find password nor keyfile while migrating from non-credentials SSH configuration!");
            }

            final SystemCredentialsProvider credentialsProvider = SystemCredentialsProvider.getInstance();
            final Map<Domain, List<Credentials>> credentialsMap = credentialsProvider.getDomainCredentialsMap();

            final Domain domain = Domain.global();
            credentialsMap.put(domain, Collections.<Credentials> singletonList(credentialsToCreate));

            credentialsProvider.setDomainCredentialsMap(credentialsMap);
            credentialsProvider.save();

            credentialId = createdCredentialId;
        } else {
            credentialId = existingCredentials.getId();
        }

        return new CredentialsSCPSite(legacy.hostname, legacy.hostname, String.valueOf(legacy.port),
                credentialId, legacy.rootRepositoryPath);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<CredentialsSCPSite> {

        private CredentialsMatcher CREDENTIALS_MATCHER = CredentialsMatchers.anyOf(
                CredentialsMatchers.instanceOf(StandardUsernameCredentials.class),
                CredentialsMatchers.instanceOf(SSHUserPrivateKey.class)
        );

        @Override
        public String getDisplayName() {
            return "";
        }

        public FormValidation doCheckKeyfile(@QueryParameter String keyfile) {
            keyfile = Util.fixEmpty(keyfile);
            if (keyfile != null) {
                File f = new File(keyfile);
                if (!f.isFile()) {
                    return FormValidation.error(Messages.SCPRepositoryPublisher_KeyFileNotExist());
                }
            }

            return FormValidation.ok();
        }

        public FormValidation doLoginCheck(@QueryParameter String hostname, @QueryParameter String port, @QueryParameter String credentialsId) {
            hostname = Util.fixEmpty(hostname);
            if (hostname == null) {// hosts is not entered yet
                return FormValidation.ok();
            }
            CredentialsSCPSite site = new CredentialsSCPSite("", hostname, port, credentialsId, "");

            try {
                Session session = site.createSession(new PrintStream(
                        System.out));
                site.closeSession(new PrintStream(System.out), session,
                        null);
            } catch (JSchException e) {
                LOGGER.log(Level.SEVERE, e.getMessage(), e);
                return FormValidation.error(e,Messages.SCPRepositoryPublisher_NotConnect());
            }
            return FormValidation.ok("Success!");
        }

        public ListBoxModel doFillCredentialsIdItems(@AncestorInPath ItemGroup context, @QueryParameter String hostname) {
            StandardListBoxModel result = new StandardListBoxModel();
            result.withEmptySelection();
            result.withMatching(CREDENTIALS_MATCHER,
                    CredentialsProvider.lookupCredentials(
                            StandardUsernameCredentials.class,
                            context,
                            ACL.SYSTEM,
                            URIRequirementBuilder.create().withHostname(hostname).build()
                    )
            );
            return result;
        }
    }
}
