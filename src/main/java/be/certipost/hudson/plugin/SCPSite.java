package be.certipost.hudson.plugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Hudson.MasterComputer;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import hudson.util.FormValidation;

/**
 *
 * @author Ramil Israfilov
 *
 */
public class SCPSite extends AbstractDescribableImpl<SCPSite> {
    String displayname;
    String hostname;
    int port;
    String username;
    String password;
    String keyfile;
    String rootRepositoryPath;

    public static final Logger LOGGER = Logger.getLogger(SCPSite.class
            .getName());

    @DataBoundConstructor
    public SCPSite(String displayname, String hostname, String port,
                   String username, String password, String keyfile,
                   String rootRepositoryPath) {
        this.displayname = displayname;
        this.hostname = hostname;
        try {
            this.port = Integer.parseInt(port);
        } catch (Exception e) {
            this.port = 22;
        }
        this.username = username;
        this.password = password;
        this.keyfile = keyfile;
        if (rootRepositoryPath != null) {
            this.rootRepositoryPath = rootRepositoryPath.trim();
        }
    }

    public String getKeyfile() {
        return keyfile;
    }

    public void setKeyfile(String keyfile) {
        this.keyfile = keyfile;
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

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
        
		// Sets a custom JSch logger to write additional info to the JDK logger
		// of this class
        JSch.setLogger(new JSchJDKLogger(LOGGER));

        Session session = jsch.getSession(username, hostname, port);
        if (this.keyfile != null && this.keyfile.length() > 0) {
            jsch.addIdentity(this.keyfile, this.password);
        } else {
            session.setPassword(password);
        }

        UserInfo ui = new SCPUserInfo(password);
        session.setUserInfo(ui);

        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");

        session.setConfig(config);
        session.connect();

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

    @Extension
    public static class DescriptorImpl extends Descriptor<SCPSite> {
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

        public FormValidation doLoginCheck(@QueryParameter String hostname, @QueryParameter String port, @QueryParameter String username, @QueryParameter String password, @QueryParameter String keyfile) {
            hostname = Util.fixEmpty(hostname);
            if (hostname == null) {// hosts is not entered yet
                return FormValidation.ok();
            }
            SCPSite site = new SCPSite("", hostname, port, username, password, keyfile, "");
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
    }
}
