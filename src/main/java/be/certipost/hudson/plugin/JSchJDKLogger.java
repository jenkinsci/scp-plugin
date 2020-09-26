/*
 * The MIT License (MIT)
 * 
 * Copyright (c) 2016 Anthony Wat
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package be.certipost.hudson.plugin;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * An implementation of the JSch logger which writes log to a JDK logger.
 * 
 * @author Anthony Wat
 *
 */
public class JSchJDKLogger implements com.jcraft.jsch.Logger {

	/**
	 * The JDK logger to log to.
	 */
	private Logger logger;

	/**
	 * Creates a new <code>JSchJDKLogger</code> instance.
	 * 
	 * @param logger
	 *            The JDK logger to log to.
	 */
	public JSchJDKLogger(Logger logger) {
		this.logger = logger;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.jcraft.jsch.Logger#isEnabled(int)
	 */
	public boolean isEnabled(int level) {
		Level jdkLogLevel = null;
		if (logger == null || (jdkLogLevel = toJDKLogLevel(level)) == Level.OFF) {
			return false;
		}
		// Return true if the JDK logger level emcompasses the given JSch log
		// level
		return logger.getLevel().intValue() <= jdkLogLevel.intValue();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see com.jcraft.jsch.Logger#log(int, java.lang.String)
	 */
	public void log(int level, String message) {
		if (logger != null) {
			logger.log(toJDKLogLevel(level), message);
		}
	}

	/**
	 * Returns the equivalent JDK log level given the JSch log level.
	 * 
	 * @param level
	 *            The log level.
	 * @return The equivalent JDK logger log level.
	 */
	private Level toJDKLogLevel(int level) {
		if (level == com.jcraft.jsch.Logger.DEBUG) {
			return Level.ALL;
		} else if (level == com.jcraft.jsch.Logger.INFO) {
			return Level.INFO;
		} else if (level == com.jcraft.jsch.Logger.WARN) {
			return Level.WARNING;
		} else if (level == com.jcraft.jsch.Logger.ERROR || level == com.jcraft.jsch.Logger.FATAL) {
			return Level.SEVERE;
		}
		return Level.OFF;
	}

}
