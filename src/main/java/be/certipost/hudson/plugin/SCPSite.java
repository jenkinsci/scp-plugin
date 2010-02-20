package be.certipost.hudson.plugin;

import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Node;
import hudson.model.Hudson.MasterComputer;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;

import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.jcraft.jsch.UserInfo;

/**
 * 
 * @author Ramil Israfilov
 * 
 */
public class SCPSite {
	String hostname;
	int port;
	String username;
	String password;
	String keyfile;
	String rootRepositoryPath;

	public static final Logger LOGGER = Logger.getLogger(SCPSite.class
			.getName());

	public SCPSite() {

	}

	public SCPSite(String hostname, int port, String username, String password,
			String rootRepositoryPath) {
		this.hostname = hostname;
		this.port = port;
		this.username = username;
		this.password = password;
		this.rootRepositoryPath = rootRepositoryPath.trim();
	}

	public SCPSite(String hostname, String port, String username,
			String password) {
		this.hostname = hostname;
		try {
			this.port = Integer.parseInt(port);
		} catch (Exception e) {
			this.port = 22;
		}
		this.username = username;
		this.password = password;
	}

	public SCPSite(String hostname, String port, String username,
			String passphrase, String keyfile) {
		this(hostname, port, username, passphrase);

		this.keyfile = keyfile;
	}

	public String getKeyfile() {
		return keyfile;
	}

	public void setKeyfile(String keyfile) {
		this.keyfile = keyfile;
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
		return hostname;
	}

	public Session createSession(PrintStream logger) throws JSchException {
		JSch jsch = new JSch();

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

	public void upload(String folderPath, FilePath filePath,
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
							envVars, logger, channel);
				}
			}
		} else {
			String localfilename = filePath.getName();
			mkdirs(folderPath, logger, channel);

			// Fix for mkdirs
			String strWorkspacePath = envVars.get("strWorkspacePath");
			String strRelativePath = extractRelativePath(strWorkspacePath,
					filePath, logger);

			String strTmp = concatDir(folderPath, strRelativePath);
			String strNewPath = concatDir(rootRepositoryPath, strTmp);
			if (!strRelativePath.equals("")) {
				// System.out.println("SCPSite.upload()   mkdirs(strTmp = "
				// + strTmp);
				// Make subdirs
				mkdirs(strTmp, logger, channel);
			}

			if (!strNewPath.endsWith("/")) {
				strNewPath += "/";
			}

			String strNewFilename = strNewPath + localfilename;

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
					channel.mkdir(curdir + "/" + pathnames[i]);
				} else {
					if (!dirstat.isDir()) {
						throw new IOException(curdir + "/" + pathnames[i]
								+ " is not a directory:" + dirstat);
					}
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

		// System.out.println("SCPSite.concatDir()strTmp = " + strTmp);

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
}
