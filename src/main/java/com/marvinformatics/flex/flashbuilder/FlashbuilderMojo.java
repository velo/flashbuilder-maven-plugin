package com.marvinformatics.flex.flashbuilder;

import static com.google.common.collect.Collections2.filter;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Lists.transform;
import static freemarker.template.Configuration.VERSION_2_3_25;

import java.io.*;
import java.net.URL;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.model.Resource;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.io.Files;
import com.google.common.io.Resources;

import freemarker.template.Configuration;
import freemarker.template.Template;
import freemarker.template.TemplateException;

@Mojo(name = "flashbuilder", requiresDependencyResolution = ResolutionScope.COMPILE)
public class FlashbuilderMojo
		extends AbstractMojo {

	private final class ReatorFilter implements Predicate<MavenProject> {
		private final Artifact artifact;

		private ReatorFilter(Artifact artifact) {
			this.artifact = artifact;
		}

		@Override
		public boolean apply(MavenProject project) {
			return project.getGroupId().equals(artifact.getGroupId())
					&& project.getArtifactId().equals(artifact.getArtifactId())
					&& project.getVersion().equals(artifact.getVersion());
		}
	}

	/**
	 * Location of the file.
	 */
	@Parameter(defaultValue = "${project.build.directory}", property = "outputDir", required = true)
	private File outputDirectory;

	@Component
	protected MavenProject project;

	@Parameter(defaultValue = "${reactorProjects}")
	private List<MavenProject> reactorProjects;

	public void execute()
			throws MojoExecutionException {
		Map<String, Object> dataModel = createDataModel();

		if ("swc".equals(project.getPackaging()))
			generateSwc(dataModel);
		else if ("swf".equals(project.getPackaging()))
			generateSwf(dataModel);

	}

	private Map<String, Object> createDataModel() {
		Map<String, Object> model = new HashMap<String, Object>();
		model.put("project", project);
		model.put("dependencies", dependencies());
		model.put("sources", sources());
		model.put("mainApplication", mainApplication());
		model.put("configXml", canonical(
				new File(project.getBuild().getDirectory(), project.getBuild().getFinalName() + "-configs.xml")));
		return model;
	}

	private String mainApplication() {
		File[] files = new File(project.getBasedir(), "src/main/flex").listFiles(new FileFilter() {
			@Override
			public boolean accept(File file) {
				return file.isFile();
			}
		});

		if (files != null && files.length > 0)
			return files[0].getName();

		return null;
	}

	private Collection<String> sources() {
		List<String> sources = Lists.newArrayList();
		sources.addAll(Lists.transform(project.getResources(), new Function<Resource, String>() {
			@Override
			public String apply(Resource input) {
				return input.getDirectory();
			}
		}));
		sources.addAll(project.getCompileSourceRoots());
		return Collections2.filter(sources, new Predicate<String>() {
			@Override
			public boolean apply(String input) {
				return new File(input).exists();
			}
		});
	}

	private List<Dependency> dependencies() {
		return transform(newArrayList(filter(project.getArtifacts(), new Predicate<Artifact>() {
			@Override
			public boolean apply(Artifact input) {
				return input.getType().equals("swc") && !input.getGroupId().contains("org.apache.flex");
			}
		})), new Function<Artifact, Dependency>() {
			@Override
			public Dependency apply(Artifact artifact) {
				if (isReactorArtifact(artifact)) {
					MavenProject localProject = getReactorArtifact(artifact);
					return new Dependency(
							path(new File(localProject.getBasedir(), "bin/" + artifact.getArtifactId() + ".swc")));
				}
				return new Dependency(path(artifact.getFile()));
			}
		});
	}

	protected boolean isReactorArtifact(final Artifact artifact) {
		return !Collections2.filter(reactorProjects, new ReatorFilter(artifact)).isEmpty();
	}

	protected MavenProject getReactorArtifact(final Artifact artifact) {
		return Collections2.filter(reactorProjects, new ReatorFilter(artifact)).iterator().next();
	}

	protected String path(File file) {
		return canonical(file).replace("\\", "/");
	}

	private String canonical(File file) {
		try {
			return file.getCanonicalPath();
		} catch (IOException e) {
			return file.getAbsolutePath();
		}
	}

	private void generateSwc(Map<String, Object> dataModel) throws MojoExecutionException {
		copy("swc", ".actionScriptProperties", dataModel);
		copy("swc", ".flexLibProperties", dataModel);
		copy("swc", ".project", dataModel);
	}

	private void generateSwf(Map<String, Object> dataModel) throws MojoExecutionException {
		copy("swf", ".actionScriptProperties", dataModel);
		copy("swf", ".flexProperties", dataModel);
		copy("swf", ".project", dataModel);
	}

	private void copy(String baseDir, String file, Map<String, Object> dataModel) throws MojoExecutionException {
		URL source = getClass().getResource("/" + baseDir + "/" + file);
		File dest = new File(project.getBasedir(), file);

		Configuration config = new Configuration(VERSION_2_3_25);
		try (Reader reader = Resources.asCharSource(source, Charsets.UTF_8).openStream();
				Writer output = Files.asCharSink(dest, Charsets.UTF_8).openBufferedStream();) {

			Template freemarkerTemplate = new Template(source.getPath(), reader, config);
			freemarkerTemplate.process(dataModel, output);

		} catch (TemplateException | IOException e) {
			throw new MojoExecutionException("", e);
		}
	}

}
