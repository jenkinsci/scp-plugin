package be.certipost.hudson.plugin;

import com.jcraft.jsch.UserInfo;

public class SCPUserInfo implements UserInfo {

	String password;
	
	public SCPUserInfo(String password){
		this.password=password;
		
	}
	public String getPassphrase() {
		return null;
	}

	public String getPassword() {
		return password;
	}

	public boolean promptPassphrase(String arg0) {
		return true;
	}

	public boolean promptPassword(String arg0) {
		return true;
	}

	public boolean promptYesNo(String arg0) {
		return true;
	}

	public void showMessage(String arg0) {
		System.out.println(arg0);

	}

}
