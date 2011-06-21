package be.certipost.hudson.plugin;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.Result;

import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

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
public final class SitePublisher {

	public static final Logger LOGGER = Logger
		.getLogger(SitePublisher.class.getName());

	/**
	 * Name of the scp site to post a file to.
	 */
	private String siteName;

	/**
	 * File copy entries for the site.
	 */
	private final List<Entry> entries;

    @DataBoundConstructor
    public SitePublisher(String siteName, List<Entry> entries) {
        if (siteName == null) {
            // defaults to the first one
            SCPSite[] sites = SCPRepositoryPublisher.DESCRIPTOR.getSites();
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

	/**
	 * @return the site corresponding to the siteName field, the first
	 * listed site if siteName is null or null if the named site is not
	 * found
	 */
	public SCPSite getSite() {
		SCPSite[] sites = SCPRepositoryPublisher.DESCRIPTOR.getSites();

		if (siteName == null && sites.length > 0)
			// default
			return sites[0];

		for (SCPSite site : sites) {
			if (site.getName().equals(siteName))
				return site;
		}
		return null;
	}

	/**
	 * Perform the build output uploads for this site.
	 * @param build
	 * @param launcher
	 * @param listener
	 * @param logger
	 * @param envVars
	 * @throws InterruptedException
	 */
	public void performUpload(AbstractBuild<?, ?> build, Launcher launcher,
			BuildListener listener, PrintStream logger,
			Map<String, String> envVars)
	throws InterruptedException {

		SCPSite scpsite = null;
		Session session = null;
		ChannelSftp channel = null;
		try {
			scpsite = getSite();
			if (scpsite == null) {
				SCPRepositoryPublisher.log(logger,
						"No SCP site is configured. This is likely a configuration problem.");
				build.setResult(Result.UNSTABLE);
				return;
			}
			if (entries == null) {
				SCPRepositoryPublisher.log(logger,
						"No SCP entries are configured for site \"" + getSiteName() + "\"");
				return;
			}
			SCPRepositoryPublisher.log(logger, "Connecting to " + scpsite.getHostname());
			session = scpsite.createSession(logger);
			channel = scpsite.createChannel(logger, session);

			for (Entry e : entries) {
				String expanded = Util.replaceMacro(e.sourceFile, envVars);
				FilePath ws = build.getWorkspace();
				FilePath[] src = ws.list(expanded);
				if (src.length == 0) {
					// try to do error diagnostics
					SCPRepositoryPublisher.log(logger, ("No file(s) found: " + expanded));
					String error = ws.validateAntFileMask(expanded);
					if (error != null)
						SCPRepositoryPublisher.log(logger, error);
					continue;
				}
				String folderPath = Util.replaceMacro(e.filePath, envVars);

				// Fix for recursive mkdirs
				folderPath = folderPath.trim();

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
			if (scpsite != null) {
				scpsite.closeSession(logger, session, channel);
			}

		}
	}

	/**
	 * @return the site name
	 */
	public String getSiteName() {
		return siteName;
	}

	/**
	 * Set the site name.
	 * @param siteName
	 */
	public void setSiteName(String siteName) {
		this.siteName = siteName;
	};
}
