package be.certipost.hudson.plugin;

import hudson.Extension;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * 
 * @author Ramil Israfilov
 *
 */
public final class Entry extends AbstractDescribableImpl<Entry> {

	/**
	 * Destination folder for the copy. May contain macros. 
	 */
	public String filePath;

	/**
	 * File name relative to the workspace root to upload. If the sourceFile is
	 * directory then all files in that directory will be copied to remote filePath directory recursively
	 * <p>
	 * May contain macro, wildcard.
	*/
	public String sourceFile;

	public boolean keepHierarchy;
	public boolean copyConsoleLog;
	public boolean copyAfterFailure;

	@DataBoundConstructor
	public Entry(String filePath, String sourceFile, boolean copyConsoleLog,
			boolean keepHierarchy, boolean copyAfterFailure) {
		this.filePath = filePath;
		this.sourceFile = sourceFile;
		this.keepHierarchy = keepHierarchy;
		this.copyConsoleLog = copyConsoleLog;
		this.copyAfterFailure = copyAfterFailure;
	}

	public String getFilePath() {
		return filePath;
	}

	public void setFilePath(String filePath) {
		this.filePath = filePath;
	}

	public String getSourceFile() {
		return sourceFile;
	}

	public void setSourceFile(String sourceFile) {
		this.sourceFile = sourceFile;
	}

	public boolean getKeepHierarchy() {
		return keepHierarchy;
	}

	public void setKeepHierarchy(boolean keepHierarchy) {
		this.keepHierarchy = keepHierarchy;
	}

	public boolean getCopyConsoleLog() {
		return copyConsoleLog;
	}

	public void setCopyConsoleLog(boolean copyConsoleLog) {
		this.copyConsoleLog = copyConsoleLog;
	}

	public boolean getCopyAfterFailure() {
		return copyAfterFailure;
	}

	public void setCopyAfterFailure(boolean copyAfterFailure) {
		this.copyAfterFailure = copyAfterFailure;
	}

	@Extension
	public static class DescriptorImpl extends Descriptor<Entry> {
		@Override
		public String getDisplayName() {
			return "";
		}
	}
}
