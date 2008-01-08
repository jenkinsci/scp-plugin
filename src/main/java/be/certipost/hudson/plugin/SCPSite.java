package be.certipost.hudson.plugin;

import hudson.FilePath;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.Map;

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
	String rootRepositoryPath;

	JSch jsch;
	private Session session;
	private ChannelSftp channel;


	public SCPSite() {

	}

	public SCPSite(String hostname, int port, String username, String password, String rootRepositoryPath) {
		this.hostname = hostname;
		this.port = port;
		this.username = username;
		this.password = password;
		this.rootRepositoryPath = rootRepositoryPath;
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
	
	public int getIntegerPort(){
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
		this.rootRepositoryPath = rootRepositoryPath;
	}

	public String getName() {
		return hostname;
	}

	public void createSession() throws JSchException {
		jsch=new JSch();
		session=jsch.getSession(username, hostname, port);
		session.setPassword(password);
		UserInfo ui = new SCPUserInfo(password);
		session.setUserInfo(ui );
		session.connect();
		channel=(ChannelSftp) session.openChannel("sftp");
		
		channel.connect();
		
	}

	public void closeSession() {
		if(channel != null){
			channel.disconnect();
			channel=null;
		}
		if(session !=null){
			session.disconnect();
			session=null;
		}
		
	}

	public void upload(String folderPath, FilePath filePath,
			Map<String, String> envVars, PrintStream logger) throws IOException, InterruptedException, SftpException {
		 
		if(session ==null || channel ==null){
			throw new IOException("Connection to "+hostname+", user="+username+" is not established");
		}
		SftpATTRS rootdirstat = channel.stat(rootRepositoryPath);
		if(rootdirstat == null){
			throw new IOException("Can't get stat of root repository directory:"+rootRepositoryPath);
		}else{
			if(!rootdirstat.isDir()){
				throw new IOException(rootRepositoryPath+" is not a directory");
			}
		}
		if(filePath.isDirectory()){
			FilePath[] subfiles = filePath.list("**/*");
			if(subfiles != null){
				for (int i = 0; i < subfiles.length; i++) {
					upload(folderPath+"/"+filePath.getName(), subfiles[i], envVars, logger);
				}
			}
		}else{
			String localfilename=filePath.getName();
			mkdirs(folderPath, logger);	
			InputStream in = filePath.read();
			channel.put(in, rootRepositoryPath+"/"+folderPath+"/"+localfilename);
			in.close();
		}
		
		
	}
	
	private void mkdirs(String filePath, PrintStream logger) throws SftpException, IOException {
		String pathnames[]=filePath.split("/");
		String curdir = rootRepositoryPath;
		if(pathnames != null){
			for (int i = 0; i < pathnames.length; i++) {
				if(pathnames[i].length()==0){
					continue;
				}
				
				SftpATTRS dirstat = null;
				try{
					dirstat=channel.stat(curdir+"/"+pathnames[i]);
				}catch(SftpException e){
					
					if(e.getMessage() != null && e.getMessage().indexOf("No such file") == -1){
						logger.println("Error getting stat of  directory:"+curdir+"/"+pathnames[i]+":"+e.getMessage());
						throw e;
					}
				}
				if(dirstat == null){
					//try to create dir
					logger.println("Trying to create "+curdir+"/"+pathnames[i]);
					channel.mkdir(curdir+"/"+pathnames[i]);
				}else{
					if(!dirstat.isDir()){
						throw new IOException(curdir+"/"+pathnames[i]+" is not a directory:"+dirstat);
					}
				}
				curdir = curdir+"/"+pathnames[i];
			}
		}
	}
	
	
	
}
