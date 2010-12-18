package be.certipost.hudson.plugin;

import org.kohsuke.stapler.DataBoundConstructor;

/**
 * 
 * @author Ramil Israfilov
 *
 */
public final class Entry {

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
}
