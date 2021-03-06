/*******************************************************************************
 * Copyright (c) 2012 VMWare, Inc.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 * VMWare, Inc. - initial API and implementation
 *******************************************************************************/
package org.springsource.ide.eclipse.gradle.core.preferences;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.PatternSyntaxException;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.FileLocator;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.IPreferenceChangeListener;
import org.eclipse.core.runtime.preferences.IEclipsePreferences.PreferenceChangeEvent;
import org.eclipse.jdt.launching.IVMInstall;
import org.osgi.framework.Bundle;
import org.osgi.service.prefs.BackingStoreException;
import org.springsource.ide.eclipse.gradle.core.GradleCore;
import org.springsource.ide.eclipse.gradle.core.util.JavaRuntimeUtils;
import org.springsource.ide.eclipse.gradle.core.wtp.DeploymentExclusions;
import org.springsource.ide.eclipse.gradle.core.wtp.RegexpListDeploymentExclusions;
import org.springsource.ide.eclipse.gradle.core.wtp.WTPUtil;


/**
 * Convenience wrapper class to access Gradle preferences, however they may be stored.
 * 
 * @author Kris De Volder
 */
public class GradlePreferences extends AbstractGradlePreferences implements IPreferenceChangeListener {

	private static final String JAVA_HOME_JRE_NAME = GradlePreferences.class.getName()+".JAVA_HOME_MODE";
	private static final String DISTRIBUTION = GradlePreferences.class.getName()+".DISTRIBUTION";
	
	private static final String DEPLOYMENT_EXCLUSIONS = GradlePreferences.class.getName()+".WTP_EXCLUDE";
	
	private static final String DISABLE_UNDERLINING = GradlePreferences.class.getName()+".DISABL_UNDERLINING";
	public static final boolean DEFAULT_DISABLE_UNDERLINING = true;
	
	private static final String JVM_ARGS = GradlePreferences.class.getName()+".JVM_ARGS";
	private static final String DEFAULT_JVM_ARGS = null;
	
	private static final String PROGRAM_ARGUMENTS = GradlePreferences.class.getName()+".PGM_ARGS";
	private static final String DEFAULT_PROGRAM_ARGUMENTS = null;

	private static URI builtInDistribution = null;

	private RegexpListDeploymentExclusions cachedExclusions;
	private IEclipsePreferences eclipsePrefs;
	
	public GradlePreferences(IEclipsePreferences prefs) {
		super();
		this.eclipsePrefs = prefs;
		eclipsePrefs.addPreferenceChangeListener(this);
	}

	/**
	 * Return a URI pointing to a distribution chosen by the user, or null if none was user specified.
	 */
	public URI getDistribution() {
		String distributionString = get(DISTRIBUTION, null);
		if (distributionString!=null) {
			try {
				return new URI(distributionString);
			} catch (URISyntaxException e) {
				GradleCore.log(e);
			}
		}
		return null;
	}

	/**
	 * Set a URI pointing to a distribution chosen by the user, or null if none was user specified.
	 */
	public void setDistribution(URI distro) {
		put(DISTRIBUTION, distro!=null?distro.toString():null);
	}
	

	/**
	 * @return a workspace configured JVM to use for Gradle's JAVA_HOME. May be null in which case
	 * Gradle should use its own default.
	 */
	public IVMInstall getJavaHomeJRE() {
		String jreName = get(JAVA_HOME_JRE_NAME, null);
		if (jreName!=null) {
			JavaRuntimeUtils jres = new JavaRuntimeUtils();
			return jres.getInstall(jreName);
		}
		return null;
	}
	
	/**
	 * Sets the JavaHome to a specific JVM installation chosen by the user, or null if no Java home was specified
	 * by the user (in this case, Gradle will be left to figure out by itself what it should be using.
	 */
	public void setJavaHomeJRE(IVMInstall install) {
		if (install==null) {
			put(JAVA_HOME_JRE_NAME, null);
		} else {
			put(JAVA_HOME_JRE_NAME, install.getName());
		}
	}

	/**
	 * @return URI of a gradle distribution that is packaged up with GradleCore plugin.
	 * @throws IOException if there is some problem getting the embedded distribution.
	 */
	public static URI getBuiltinDistribution() throws IOException {
		if (builtInDistribution==null) {
			debug(">>>> Searching for embedded Gradle install");
			Bundle coreBundle = Platform.getBundle(GradleCore.PLUGIN_ID);
			File coreBundleFile = FileLocator.getBundleFile(coreBundle);
			Assert.isNotNull(coreBundleFile);
			Assert.isLegal(coreBundleFile.isDirectory(), "The bundle "+coreBundle.getSymbolicName()+" must be unpacked to allow using the embedded gradle distribution");
			File libDir = new File(coreBundleFile, "lib");
			File[] candidates = libDir.listFiles(new FilenameFilter() {
				public boolean accept(File dir, String name) {
					return name.endsWith(".zip");
				}
			});
			if (GlobalSettings.DEBUG) {
				for (File file : candidates) {
					debug("found: "+file);
				}
			}
			Assert.isTrue(candidates.length<=1, "At most one embedded distribution should be found!");
			if (candidates.length==1) {
				builtInDistribution = candidates[0].toURI();
				debug("Found embedded install: "+builtInDistribution);
			} else {
				debug("No embedded install found");
			}
			debug("<<<< Searching for embedded Gradle install");
		}
		return builtInDistribution;
	}

	private static void debug(String string) {
		if (GlobalSettings.DEBUG) {
			System.out.println(string);
		}
	}
	
	public RegexpListDeploymentExclusions getDeploymentExclusions() {
		if (cachedExclusions==null) {
			cachedExclusions = createDeploymentExclusions();
		}
		return cachedExclusions;
	}

	private RegexpListDeploymentExclusions createDeploymentExclusions() {
		String[] regexps = getStrings(DEPLOYMENT_EXCLUSIONS, null);
		if (regexps!=null) {
			return new RegexpListDeploymentExclusions(regexps);
		}
		return DeploymentExclusions.getDefault();
	}

	/**
	 * Sets the WTP deployment exclusions. May throw an exception if the exclusion expression have syntax errors.
	 */
	public void setDeploymentExclusions(RegexpListDeploymentExclusions exclusions) throws PatternSyntaxException {
		exclusions.verify(); //Only verified exclusions will be accepted!
		RegexpListDeploymentExclusions current = getDeploymentExclusions();
		if (!current.equals(exclusions)) { //Avoid uneccesary change events.
			putStrings(DEPLOYMENT_EXCLUSIONS, exclusions.getSourceExps());
		}
	}

	@Override
	public void put(String name, String value) {
		if (value==null) {
			eclipsePrefs.remove(name);
		} else {
			eclipsePrefs.put(name, value);
		}
		try {
			eclipsePrefs.flush();
		} catch (BackingStoreException e) {
			GradleCore.log(e);
		}
	}
	
	@Override
	public String get(String name, String deflt) {
		return eclipsePrefs.get(name, deflt);
	}

	public void dispose() {
	}

	public void preferenceChange(PreferenceChangeEvent event) {
		if (DEPLOYMENT_EXCLUSIONS.equals(event.getKey())) {
			cachedExclusions = null;
			WTPUtil.refreshAllDependencies();
		}
	}

	public boolean getGroovyEditorDisableUnderlining() {
		return get(DISABLE_UNDERLINING, DEFAULT_DISABLE_UNDERLINING);
	}
	
	public void setGroovyEditorDisableUnderlining(boolean disable) {
		put(DISABLE_UNDERLINING, disable);
	}

	/**
	 * Return custom JVM arguments for the Gradle Daemon. May return null, in which case
	 * it means we want to let Gradle use its own defaults.
	 */
	public String getJVMArguments() {
		return get(JVM_ARGS, DEFAULT_JVM_ARGS);
	}
	
	public void setJVMArguments(String args) {
		put(JVM_ARGS, args);
	}

	public String getProgramArguments() {
		return get(PROGRAM_ARGUMENTS, DEFAULT_PROGRAM_ARGUMENTS);
	}

	public void setProgramArguments(String args) {
		put(PROGRAM_ARGUMENTS, args);
	}
	
}
