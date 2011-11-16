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

    @DataBoundConstructor
    public Entry(String filePath, String sourceFile, boolean keepHierarchy) {
        this.filePath = filePath;
        this.sourceFile = sourceFile;
        this.keepHierarchy = keepHierarchy;
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Entry> {
        @Override
        public String getDisplayName() {
            return "";
        }
    }
}
