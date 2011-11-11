/* -*- java -*-
 * This is free software, licensed under the Gnu Public License (GPL)
 * get a copy from <http://www.gnu.org/licenses/gpl.html>
 * $Id: Version.java.in,v 1.1 2002-03-11 08:39:09 hzeller Exp $ 
 * author: Henner Zeller <H.Zeller@acm.org>
 */
package henplus;

import java.io.IOException;
import java.net.URL;
import java.text.DateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Contains version information
 */
public class Version {

	static {
		new Version();
	}

	private static String version = "0.0.0";
	private static String versionTitle = "Development";
	private static String compileTime = DateFormat.getDateTimeInstance().format(new Date());

	/** hide constructor. */
	private Version() {
		try {
			Enumeration<URL> resources = getClass().getClassLoader().getResources("META-INF/MANIFEST.MF");
			while (version == null && resources.hasMoreElements()) {
				try {
					Manifest manifest = new Manifest(resources.nextElement().openStream());
					Attributes attr = manifest.getMainAttributes();
					String value = (String) attr.get("HenPlus-Version");
					if (value != null) {
						version = value;
						versionTitle = (String) attr.get("HenPlus-VersionTitle");
						compileTime = (String) attr.get("HenPlus-CompileTime");
					}
				} catch (IOException E) {
					// handle
				}
			}
		} catch (IOException ioe) {
		}

		// noop
	}

	public static String getVersion() {
		return version;
	}

	public static String getCompileTime() {
		return compileTime;
	}

	public static String getVersionTitle() {
		return versionTitle;
	}
}

/*
 * Local variables: c-basic-offset: 4 compile-command:
 * "ant -emacs -find build.xml" End:
 */
