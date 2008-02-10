package be.certipost.hudson.plugin;

import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Build;
import hudson.model.BuildListener;
import hudson.model.Descriptor;
import hudson.model.Result;
import hudson.tasks.Publisher;
import hudson.util.CopyOnWriteList;
import hudson.util.FormFieldValidator;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;

import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.SftpException;

/**
 * 
 * @author Ramil Israfilov
 *
 */
public class SCPRepositoryPublisher extends Publisher {

	/**
	 * Name of the scp site to post a file to.
	 */
	private String siteName;


	private final List<Entry> entries = new ArrayList<Entry>();

	public SCPRepositoryPublisher() {
	}
	
	public SCPRepositoryPublisher(String siteName){
		if (siteName == null) {
			// defaults to the first one
			SCPSite[] sites = DESCRIPTOR.getSites();
			if (sites.length > 0)
				siteName = sites[0].getName();
		}
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

	public boolean perform(Build build, Launcher launcher,
			BuildListener listener) throws InterruptedException {
		if (build.getResult() == Result.FAILURE) {
			// build failed. don't post
			return true;
		}
		
		SCPSite scpsite = null;
		try {
			scpsite=getSite();
            if(scpsite==null) {
                listener.getLogger().println("No SCP site is configured. This is likely a configuration problem.");
                build.setResult(Result.UNSTABLE);
                return true;
            }
            listener.getLogger().println("Connecting to " + scpsite.getHostname());
			scpsite.createSession();
			

			Map<String, String> envVars = build.getEnvVars();

			for (Entry e : entries) {
				String expanded = Util.replaceMacro(e.sourceFile, envVars);
				FilePath[] src = build.getProject().getWorkspace().list(
						expanded);
				String folderPath = Util.replaceMacro(e.filePath, envVars);
				if (src.length == 0)
					listener.getLogger().println("No file(s) found: "
							+ expanded);

				if (src.length == 1) {
					listener.getLogger().println("remote folderPath " + folderPath+",local file:"+src[0].getName());
					scpsite.upload(folderPath,src[0],envVars,listener.getLogger());
				} else {
					 for( FilePath s : src ){
						 listener.getLogger().println("remote folderPath " + folderPath+",local file:"+s.getName());
						 scpsite.upload(folderPath, s, envVars,listener.getLogger());
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
		}finally{
			if(scpsite !=null){
				scpsite.closeSession();
			}
			
		}

		return true;
	}

	public Descriptor<Publisher> getDescriptor() {
		return DESCRIPTOR;
	}

	public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
	
	public static final class DescriptorImpl extends Descriptor<Publisher>{
		
		public DescriptorImpl(){
			super(SCPRepositoryPublisher.class);
			load();
		}
		
		protected DescriptorImpl(Class<? extends Publisher> clazz) {
			super(clazz);
		}

		private final CopyOnWriteList<SCPSite> sites = new CopyOnWriteList<SCPSite>();
		
		public String getDisplayName() {
			return "Publish artifacts to SCP Repository";
		}

		public String getHelpFile() {
			return "/plugin/scp/help.html";
		}

		public Publisher newInstance(StaplerRequest req) {
			SCPRepositoryPublisher pub = new SCPRepositoryPublisher();
			req.bindParameters(pub, "scp.");
			pub.getEntries().addAll(
					req.bindParametersToList(Entry.class, "scp.entry."));
			return pub;
		}
		
		public SCPSite[] getSites() {
			Iterator<SCPSite> it = sites.iterator();
			int size=0;
			while(it.hasNext()){
				it.next();
				size++;
			}
			return sites.toArray(new SCPSite[size]);
		}

		public boolean configure(StaplerRequest req) {
			sites.replaceBy(req.bindParametersToList(SCPSite.class, "scp."));
			save();
			return true;
		}
		
		public void doLoginCheck(final StaplerRequest req, StaplerResponse rsp)
				throws IOException, ServletException {
			new FormFieldValidator(req, rsp, false) {
				protected void check() throws IOException, ServletException {
					String hostname = Util.fixEmpty(request
							.getParameter("hostname"));
					if (hostname == null) {// hosts is not entered yet
						ok();
						return;
					}
					SCPSite site = new SCPSite(hostname, request
							.getParameter("port"),
							request.getParameter("user"), request
									.getParameter("pass"));
					try {
						try {
							site.createSession();
							site.closeSession();
						} catch (JSchException e) {
							throw new IOException("Can't connect to server");
						}
						
						ok();
					} catch (IOException e) {
						error(e.getMessage());

					}
				}
			}.process();

		}

	}

	public String getSiteName() {
		return siteName;
	}

	public void setSiteName(String siteName) {
		this.siteName = siteName;
	};

}