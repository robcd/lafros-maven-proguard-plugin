/**
 * Copyright 2008 Latterfrosken Software Development Limited
 *
 * This file is part of the lafros-maven-proguard plug-in.
 *
 * The plug-in is free software: you can redistribute it and/or modify 
 * it under the terms of the GNU General Public License as published by 
 * the Free Software Foundation, either version 3 of the License, or 
 * (at your option) any later version. 
 * The plug-in is distributed in the hope that it will be useful, 
 * but WITHOUT ANY WARRANTY; without even the implied warranty of 
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the 
 * GNU General Public License for more details. 
 * You should have received a copy of the GNU General Public License 
 * along with the plug-in.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.lafros.maven.plugins.proguard;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.project.MavenProject;
import proguard.ClassPath;
import proguard.ClassPathEntry;
import proguard.Configuration;
import proguard.ConfigurationParser;
import proguard.KeepSpecification;
import proguard.ParseException;
import proguard.ProGuard;
/**
 * for projects of packaging type, <b><tt>liberated-jar</tt></b>, this goal
 * 'liberates' the jar artifact, in advance of its creation, from specified library
 * dependencies which are typically large and only sparsely populated with the
 * classes which are actually required. This is achieved by copying those classes
 * to the output directory, where they will be added to the artifact by the
 * standard jar plug-in in the usual way. Other dependencies which also depend on
 * the above ones may also be specified, so that the classes which they themselves
 * require will also be copied to the output directory.
 *
 * @phase package
 * @goal liberate
 */
public class LiberatorMojo extends AbstractMojo {
  private final String STAGING_JAR_NAME = "onlyThoseRequired.jar";
  /**
   * the MavenProject
   * @parameter default-value="${project}"
   * @required
   * @readonly */
  private MavenProject project;
  /** 
   * the output directory
   * @parameter default-value="${project.build.outputDirectory}"
   * @required
   * @readonly */
  private File outDir;
  /** 
   * The set of dependencies required by the project
   * @parameter default-value="${project.artifacts}"
   * @required
   * @readonly */
  private Set<Artifact> dependencies;
  /**
   * corresponds to ProGuard's <a
   * href="http://proguard.sourceforge.net/manual/usage.html#iooptions"><tt>-libraryjars</tt></a>
   * option. If not set, <tt>"&lt;java.home&gt;/lib/rt.jar"</tt> will be used. On
   * Mac OS X, you will need to supply a <tt>&lt;param&gt;</tt> with value
   * <tt>"&lt;java.home&gt;/../Classes/classes.jar"</tt>.
   * @parameter */
  private String[] libraryJars;
  /**
   * required classes (see above) from dependency artifacts starting with any of
   * these Strings will be copied to the output directory. If not set,
   * <tt>"scala-library-"</tt> and <tt>"scala-swing-"</tt> will be assumed.
   * @parameter */
  private String[] liberateFromDepsWhoseArtsStartWith;
  /**
   * other dependency artifacts which depend on those to be liberated from - any
   * classes they require will also be copied to the output directory.
   * @parameter */
  private String[] alsoSupportDepsWhoseArtsStartWith;
  /**
   * ProGuard <a
   * href="http://proguard.sourceforge.net/manual/usage.html#filters">filter</a>, to
   * be applied when copying classes from the dependencies
   * being liberated from.
   * @parameter expression="(!scala/swing/test/**, scala/**)"*/
  private String filter;
  /**
   * fully-qualified names of declared 'main' or applet classes - those containing
   * a <tt>public static void main</tt> or extending <tt>java.applet.Applet</tt>,
   * respectively.
   * @parameter
   * @required */
  private String[] entryPoints;
  /**
   * corresponds to ProGuard's <a
   * href="http://proguard.sourceforge.net/manual/usage.html#generaloptions"><tt>-dontnote</tt></a>
   * option.
   * @parameter expression=false */
  private boolean suppressNotes;
  /**
   * corresponds to ProGuard's <a
   * href="http://proguard.sourceforge.net/manual/usage.html#generaloptions"><tt>-dontwarn</tt></a>
   * option.
   * @parameter expression=false */
  private boolean suppressWarnings;
  /**
   * corresponds to ProGuard's <a
   * href="http://proguard.sourceforge.net/manual/usage.html#generaloptions"><tt>-verbose</tt></a>
   * option.
   * @parameter expression=false */
  private boolean verbose;
  /**
   * permits execution of the goal to be disabled.
   * @parameter expression=true */
  private boolean enabled;

  public void execute() throws MojoExecutionException, MojoFailureException {
    if (!this.project.getPackaging().equals("liberated-jar"))
      return;
    //
    if (!this.enabled) {
      getLog().info("liberate goal disabled");
      return;
    }
    //
    // separate dependencies into liberate-from libraries, and other dependencies
    final Set<File> liberateFrLibs = new HashSet(), otherDeps = new HashSet(); {
      if (liberateFromDepsWhoseArtsStartWith == null)
        liberateFromDepsWhoseArtsStartWith = new String[] {
          "scala-library-",
          "scala-swing-",
        };
      for (Artifact art: dependencies) { 
        final File jar = art.getFile();
        final String name = jar.getName();
        boolean accountedFor = false;
        for (String prefix: liberateFromDepsWhoseArtsStartWith) {
          if (name.startsWith(prefix)) {
            liberateFrLibs.add(jar);
            accountedFor = true;
            break;
          }
        }
        if (!accountedFor) {
          // must start with one of the Strings in alsoSupportDepsWhoseArtsStartWith
          for (String prefix: alsoSupportDepsWhoseArtsStartWith) {
            if (name.startsWith(prefix)) otherDeps.add(jar);
          }
        }
      }
    }
    //
    // create ProGuard configuration
    final Configuration configuration = new Configuration();
    if (configuration.programJars == null)
      configuration.programJars = new ClassPath();
    else
      configuration.programJars.clear();
    {
      String step = "create parser";
      Exception ex = null;
      try {
        final ConfigurationParser parser; {
          final String[] args = createParserArgs(liberateFrLibs, otherDeps);
          if (verbose)
            for (String arg: args) {
              getLog().info("arg: "+ arg);
            }
          parser = new ConfigurationParser(args, null);
        }
        step = "parse configuration";
        if (verbose) getLog().info("parsing configuration...");
        parser.parse(configuration);
        if (verbose) getLog().info("ok");
      }
      catch (final ParseException pe) {
        ex = pe;
      }
      catch (final IOException ioe) {
        ex = ioe;
      }
      if (ex != null)
        throw new MojoExecutionException("ProGuard was unable to "+ step +": ", ex);
    }
    //
    // process said configuration
    try {
      new ProGuard(configuration).execute(); // IOException (unexpected)
    }
    catch (final IOException ex) {
      throw new MojoExecutionException("ProGuard was unable to process the configuration: ",
                                       ex);
    }
    //
    // copy contents of temp jar into outDir
    {
      final JarFile onlyThoseRequired; {
        final String name = targetPath() + STAGING_JAR_NAME;
        if (verbose) getLog().info("copying required classes from "+ name);
        try {
          onlyThoseRequired = new JarFile(name);
        }
        catch (final IOException e) {
          throw new MojoExecutionException("unable to create JarFile for name, "+
                                           name);
        }
      }
      final String prefix = outDir.getPath() + File.separator;
      final Enumeration<JarEntry> en = onlyThoseRequired.entries();
      InputStream in;
      FileOutputStream out;
      while (en.hasMoreElements()) {
        final JarEntry jarEntry = en.nextElement();
        final String path = jarEntry.getName();
        createDirectories(path);
        final File file = new File(prefix + path);
        
        try {
          in = onlyThoseRequired.getInputStream(jarEntry);
          out = new FileOutputStream(file);
          while (in.available() > 0) {
            out.write(in.read());
          }
          out.close();
          in.close();
        }
        catch (final IOException ex) {
          throw new MojoExecutionException("unable to create "+ path);
        }
      }
    }
  }
  /**
   * includes the final separator */
  private String targetPath() {
    final String baseDir = project.getBasedir().getPath();
    return baseDir + File.separator +"target"+ File.separator;
  }

  private String[] createParserArgs(final Set<File> liberateFrLibs,
                                    final Set<File> otherDeps) throws MojoExecutionException {
    final List<String> list = new ArrayList(20);
    //
    list.add("-basedirectory "+ project.getBasedir().getPath() + File.separator +"target");
    //
    list.add("-injar "+ outDir.getPath());
    for (File jar: otherDeps) {
      list.add("-injar "+ jar.getPath());
    }
    list.add("-outjar discarded");
    for (File jar: liberateFrLibs) {
      list.add("-injar "+ jar.getPath() + filter);
    }
    // ProGuard only supports output to a jar file (rather than the output directory directly)
    list.add("-outjar "+ STAGING_JAR_NAME);
    //
    if (libraryJars == null) libraryJars = new String[] {
        "<java.home>/lib/rt.jar"
      };
    for (String libraryJar: libraryJars) {
      list.add("-libraryjars "+ libraryJar);
    }
    //
    if (entryPoints == null || entryPoints.length == 0)
      throw new MojoExecutionException("Please supply entryPoints.");
    for (String entryPoint: entryPoints) { 
      list.add("-keep public class "+ entryPoint +" {*;}");
    }
    //
    list.add("-ignorewarnings");
    //
    // let's assume the following are best contemplated after jar:jar has been executed
    list.add("-dontoptimize");
    list.add("-dontobfuscate");
    list.add("-dontpreverify");
    //
    if (suppressNotes)
      list.add("-dontnote");
    if (suppressWarnings)
      list.add("-dontwarn");
    if (verbose)
      list.add("-verbose");
    //
    return list.toArray(new String[] {});
  }

  private void createDirectories(String path) throws MojoExecutionException {
    final String[] tokens = path.split(File.separator);
    // * assume two or more tokens
    // * ignore the last token (which is the file itself)
    //for (String token: tokens)
    final int n = tokens.length - 1;
    File dir = outDir;
    for (int i = 0; i < n; i++) {
      //path = path + File.separator + tokens[i];
      dir = new File(dir, tokens[i]);
      if (!dir.exists()) {
        if (!dir.mkdir()) throw new MojoExecutionException("unable to create directory: "+ dir.getPath());
        if (verbose) getLog().info("created "+ dir.getPath());
      }
    }
  }
}
