/*
 * Copyright 2010 Ning, Inc.
 *
 * Ning licenses this file to you under the Apache License, version 2.0
 * (the "License"); you may not use this file except in compliance with the
 * License.  You may obtain a copy of the License at:
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.ning.maven.plugins.duplicatefinder;

import static java.lang.String.format;

import static com.google.common.base.Preconditions.checkState;
import static com.ning.maven.plugins.duplicatefinder.DuplicateFinderMojo.ConflictState.CONFLICT_CONTENT_DIFFERENT;
import static com.ning.maven.plugins.duplicatefinder.DuplicateFinderMojo.ConflictState.CONFLICT_CONTENT_EQUAL;
import static com.ning.maven.plugins.duplicatefinder.DuplicateFinderMojo.ConflictState.NO_CONFLICT;

import static org.apache.maven.artifact.Artifact.SCOPE_COMPILE;
import static org.apache.maven.artifact.Artifact.SCOPE_PROVIDED;
import static org.apache.maven.artifact.Artifact.SCOPE_RUNTIME;
import static org.apache.maven.artifact.Artifact.SCOPE_SYSTEM;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closer;
import com.ning.maven.plugins.duplicatefinder.artifact.MatchArtifactPredicate;
import com.ning.maven.plugins.duplicatefinder.classpath.ClasspathDescriptor;
import com.pyx4j.log4j.MavenLogAppender;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DependencyResolutionRequiredException;
import org.apache.maven.artifact.versioning.InvalidVersionSpecificationException;
import org.apache.maven.model.Dependency;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds duplicate classes/resources.
 */
@Mojo(name = "check",
                requiresProject = true,
                threadSafe = true,
                defaultPhase = LifecyclePhase.VERIFY,
                requiresDependencyResolution = ResolutionScope.TEST)
public final class DuplicateFinderMojo extends AbstractMojo
{
    private static final Logger LOG = LoggerFactory.getLogger(DuplicateFinderMojo.class);

    private static final HashFunction SHA_256 = Hashing.sha256();


    enum ConflictState {
        // Conflict states in order from low to high.
        NO_CONFLICT, CONFLICT_CONTENT_EQUAL, CONFLICT_CONTENT_DIFFERENT;

        public static ConflictState max(ConflictState ... states)
        {
            checkState(states.length > 0, "states is empty");

            ConflictState result = states[0];
            for (int i = 1; i < states.length; i++) {
                if (states[i].ordinal() > result.ordinal()) {
                    result = states[i];
                }
            }

            return result;
        }
    }

    private static final Set<String> COMPILE_SCOPE = ImmutableSet.of(SCOPE_COMPILE, SCOPE_PROVIDED, SCOPE_SYSTEM);
    private static final Set<String> RUNTIME_SCOPE = ImmutableSet.of(SCOPE_COMPILE, SCOPE_RUNTIME);
    private static final Set<String> TEST_SCOPE = ImmutableSet.<String>of(); // Empty == all scopes

    /**
     * The maven project (effective pom).
     */
    @Component
    private MavenProject project;

    /**
     * Report files that have the same sha256 has value.
     *
     * @since 1.0.6
     */
    @Parameter(defaultValue = "false")
    protected boolean printEqualFiles = false;

    /**
     * Fail the build if files with the same name but different content are detected.
     *
     * @since 1.0.3
     */
    @Parameter(defaultValue = "false")
    protected boolean failBuildInCaseOfDifferentContentConflict;

    /**
     * Fail the build if files with the same name and the same content are detected.
     * @since 1.0.3
     */
    @Parameter(defaultValue = "false")
    protected boolean failBuildInCaseOfEqualContentConflict;

    /**
     * Fail the build if any files with the same name are found.
     */
    @Parameter(defaultValue = "false")
    protected boolean failBuildInCaseOfConflict;

    /**
     * Use the default resource ignore list.
     */
    @Parameter(defaultValue = "true")
    protected boolean useDefaultResourceIgnoreList = true;

    /**
     * Ignored resources, which are not checked for multiple occurences.
     */
    @Parameter
    protected String[] ignoredResources = new String [0];

    /**
     * Artifacts with expected and resolved versions that are checked.
     */
    @Parameter
    protected Exception[] exceptions;

    /**
     * Dependencies that should not be checked at all.
     */
    @Parameter(property = "ignoredDependencies")
    protected Dependency[] ignoredDependencies;

    /**
     * Check resources and classes on the compile class path.
     */
    @Parameter(defaultValue = "true")
    protected boolean checkCompileClasspath = true;

    /**
     * Check resources and classes on the runtime class path.
     */
    @Parameter(defaultValue = "true")
    protected boolean checkRuntimeClasspath = true;

    /**
     * Check resources and classes on the test class path.
     */
    @Parameter(defaultValue = "true")
    protected boolean checkTestClasspath = true;

    /**
     * Skips the plugin execution.
     */
    @Parameter(defaultValue = "false")
    protected boolean skip = false;

    /**
     * Quiets the plugin (report only errors).
     *
     * @since 1.1.0
     */
    @Parameter(defaultValue="false")
    protected boolean quiet = false;

    @Override
    public void setLog(Log log)
    {
        super.setLog(log);
        MavenLogAppender.startPluginLog(this);
    }

    @Override
    public void execute() throws MojoExecutionException
    {
        try {
            if (skip) {
                LOG.debug("Skipping execution!");
            }
            else {
                try {
                    if (checkCompileClasspath) {
                        report("Checking compile classpath");
                        Iterable<Artifact> artifacts = buildScopedArtifacts(COMPILE_SCOPE);
                        checkClasspath(project.getCompileClasspathElements(), createArtifactsByFileMap(artifacts, getOutputDirectory()));
                    }

                    if (checkRuntimeClasspath) {
                        report("Checking runtime classpath");
                        Iterable<Artifact> artifacts = buildScopedArtifacts(RUNTIME_SCOPE);
                        checkClasspath(project.getRuntimeClasspathElements(), createArtifactsByFileMap(artifacts, getOutputDirectory()));
                    }

                    if (checkTestClasspath) {
                        report("Checking test classpath");
                        Iterable<Artifact> artifacts = buildScopedArtifacts(TEST_SCOPE);
                        checkClasspath(project.getTestClasspathElements(), createArtifactsByFileMap(artifacts, getOutputDirectory(), getTestOutputDirectory()));
                    }
                }
                catch (final DependencyResolutionRequiredException e) {
                    throw new MojoExecutionException("Could not resolve dependencies", e);
                }
                catch (final InvalidVersionSpecificationException e) {
                    throw new MojoExecutionException("Invalid version specified", e);
                }
            }
        }
        finally {
            MavenLogAppender.endPluginLog(this);
        }
    }

    private Iterable<Artifact> buildScopedArtifacts(Set<String> scopes)
        throws InvalidVersionSpecificationException
    {
        final Set<Artifact> allArtifacts = project.getArtifacts();

        final ImmutableSet.Builder<Artifact> inScopeBuilder = ImmutableSet.builder();
        for (final Artifact artifact : allArtifacts) {
            if (artifact.getArtifactHandler().isAddedToClasspath()) {
                if (scopes.isEmpty() || scopes.contains(artifact.getScope())) {
                    inScopeBuilder.add(artifact);
                }
            }
        }

        return inScopeBuilder.build();
    }

    private void checkClasspath(final List<String> classpathElements, final Map<File, Optional<Artifact>> artifactsByFile) throws MojoExecutionException, InvalidVersionSpecificationException
    {
        final ClasspathDescriptor classpathDesc = createClasspathDescriptor(classpathElements, artifactsByFile);

        final ConflictState foundDuplicateClassesConflict = checkForDuplicateClasses(classpathDesc, artifactsByFile);
        final ConflictState foundDuplicateResourcesConflict = checkForDuplicateResources(classpathDesc, artifactsByFile);
        final ConflictState maxConflict = ConflictState.max(foundDuplicateClassesConflict, foundDuplicateResourcesConflict);

        if (failBuildInCaseOfConflict && maxConflict.compareTo(NO_CONFLICT) > 0  ||
            failBuildInCaseOfEqualContentConflict && maxConflict.compareTo(CONFLICT_CONTENT_EQUAL) >= 0 ||
            failBuildInCaseOfDifferentContentConflict && maxConflict.compareTo(CONFLICT_CONTENT_DIFFERENT) >= 0) {
            throw new MojoExecutionException("Found duplicate classes/resources");
        }
    }

    private ConflictState checkForDuplicateClasses(final ClasspathDescriptor classpathDesc, final Map<File, Optional<Artifact>> artifactsByFile) throws MojoExecutionException
    {
        final Multimap<String, String> classDifferentConflictsByArtifactNames = MultimapBuilder.treeKeys(new ToStringComparator()).linkedListValues().build();
        final Multimap<String, String> classEqualConflictsByArtifactNames = MultimapBuilder.treeKeys(new ToStringComparator()).linkedListValues().build();

        for (final Map.Entry<String, Collection<File>> entry : classpathDesc.getClasses().entrySet()) {
            final String className = entry.getKey();
            final Collection<File> elements = entry.getValue();

            if (elements.size() > 1) {
                final Set<Artifact> artifacts = getArtifactsForElements(elements, artifactsByFile);

                if (artifacts.size() < 2 || isExceptedClass(className, artifacts)) {
                    continue;
                }

                final String artifactNames = getArtifactsToString(artifacts);

                if (isAllElementsAreEqual(elements, className.replace('.', '/') + ".class")) {
                    classEqualConflictsByArtifactNames.put(artifactNames, className);
                }
                else {
                    classDifferentConflictsByArtifactNames.put(artifactNames, className);
                }
            }
        }

        ConflictState conflict = NO_CONFLICT;

        if (!classEqualConflictsByArtifactNames.isEmpty()) {
            if (printEqualFiles ||
                failBuildInCaseOfConflict ||
                failBuildInCaseOfEqualContentConflict) {

                printWarningMessage(classEqualConflictsByArtifactNames, "(but equal)", "classes");
            }

            conflict = CONFLICT_CONTENT_EQUAL;
        }

        if (!classDifferentConflictsByArtifactNames.isEmpty()) {
            printWarningMessage(classDifferentConflictsByArtifactNames, "and different", "classes");

            conflict = CONFLICT_CONTENT_DIFFERENT;
        }

        return conflict;
    }

    private ConflictState checkForDuplicateResources(final ClasspathDescriptor classpathDesc, final Map<File, Optional<Artifact>> artifactsByFile) throws MojoExecutionException
    {
        final Multimap<String, String> resourceDifferentConflictsByArtifactNames = MultimapBuilder.treeKeys(new ToStringComparator()).linkedListValues().build();
        final Multimap<String, String> resourceEqualConflictsByArtifactNames = MultimapBuilder.treeKeys(new ToStringComparator()).linkedListValues().build();

        for (final Map.Entry<String, Collection<File>> entry : classpathDesc.getResources().entrySet()) {
            final String resource = entry.getKey();
            final Collection<File> elements = entry.getValue();

            if (elements.size() > 1) {
                final Set<Artifact> artifacts = getArtifactsForElements(elements, artifactsByFile);

                if (artifacts.size() < 2 || isExceptedResource(resource, artifacts)) {
                    continue;
                }

                final String artifactNames = getArtifactsToString(artifacts);
                if (isAllElementsAreEqual(elements, resource)) {
                    resourceEqualConflictsByArtifactNames.put(artifactNames, resource);
                }
                else {
                    resourceDifferentConflictsByArtifactNames.put(artifactNames, resource);
                }
            }
        }

        ConflictState conflict = NO_CONFLICT;

        if (!resourceEqualConflictsByArtifactNames.isEmpty()) {
            if (printEqualFiles ||
                failBuildInCaseOfConflict ||
                failBuildInCaseOfEqualContentConflict) {

                printWarningMessage(resourceEqualConflictsByArtifactNames, "(but equal)", "resources");
            }

            conflict = CONFLICT_CONTENT_EQUAL;
        }

        if (!resourceDifferentConflictsByArtifactNames.isEmpty()) {
            printWarningMessage(resourceDifferentConflictsByArtifactNames, "and different", "resources");

            conflict = CONFLICT_CONTENT_DIFFERENT;
        }

        return conflict;
    }

    /**
     * Prints the conflict messages.
     *
     * @param conflictsByArtifactNames the Map of conflicts (Artifactnames, List of classes)
     * @param hint hint with the type of the conflict ("all equal" or "content different")
     * @param type type of conflict (class or resource)
     */
    private void printWarningMessage(Multimap<String, String> conflictsByArtifactNames, final String hint, final String type)
    {
        for (final Map.Entry<String, Collection<String>> entry : conflictsByArtifactNames.asMap().entrySet()) {
            final String artifactNames = entry.getKey();
            final Collection<String> classNames = entry.getValue();

            LOG.warn("Found duplicate " + hint + " " + type + " in " + artifactNames + " :");
            for (String className : classNames) {
                LOG.warn("  " + className);
            }
        }
    }

    /**
     * Detects class/resource differences via SHA256 hash comparsion.
     *
     * @param resourcePath the class or resource path that has duplicates in classpath
     * @param elements the files contains the duplicates
     * @return true if all classes are "byte equal" and false if any class differ
     */
    private boolean isAllElementsAreEqual(final Iterable<File> elements, final String resourcePath)
    {
        File firstFile = null;
        String firstSHA256 = null;

        for (File element : elements)
        {
            try {
                final String newSHA256 = getSHA256HexOfElement(element, resourcePath);

                if (firstSHA256 == null) {
                    // save sha256 hash from the first element
                    firstSHA256 = newSHA256;
                    firstFile = element;
                }
                else if (!newSHA256.equals(firstSHA256)) {
                    LOG.debug("Found different SHA256 hashes for elements " + resourcePath + " in file " + firstFile + " and " + element);
                    return false;
                }
            }
            catch (final IOException ex) {
                LOG.warn("Could not read content from file " + element + "!", ex);
            }
        }

        return true;
    }

    /**
     * Calculates the SHA256 Hash of a class in a file.
     *
     * @param file the archive contains the class
     * @param resourcePath the name of the class
     * @return the MD% Hash as Hex-Value
     * @throws IOException if any error occurs on reading class in archive
     */
    private String getSHA256HexOfElement(final File file, final String resourcePath) throws IOException
    {
        Closer closer = Closer.create();
        InputStream in;

        try {
            if (file.isDirectory()) {
                final File resourceFile = new File(file, resourcePath);
                in = closer.register(new BufferedInputStream(new FileInputStream(resourceFile)));
            }
            else {
                final ZipFile zip = new ZipFile(file);

                closer.register(new Closeable() {
                    @Override
                    public void close() throws IOException {
                        zip.close();
                    }
                });

                final ZipEntry zipEntry = zip.getEntry(resourcePath);

                if (zipEntry == null) {
                    throw new IOException("Could not find " + resourcePath + " in archive " + file);
                }

                in = zip.getInputStream(zipEntry);
            }

            return SHA_256.newHasher().putBytes(ByteStreams.toByteArray(in)).hash().toString();
        }
        finally {
            closer.close();
        }
    }

    private boolean isExceptedClass(final String className, final Collection<Artifact> artifacts)
    {
        final List<Exception> exceptions = getExceptionsFor(artifacts);

        for (Exception exception : exceptions) {
            if (exception.containsClass(className)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExceptedResource(final String resource, final Collection<Artifact> artifacts)
    {
        final List<Exception> exceptions = getExceptionsFor(artifacts);

        for (Exception exception : exceptions) {
            if (exception.containsResource(resource)) {
                return true;
            }
        }
        return false;
    }

    private List<Exception> getExceptionsFor(final Collection<Artifact> artifacts)
    {
        final List<Exception> result = new ArrayList<Exception>();

        if (exceptions != null) {
            for (Exception exception : Arrays.asList(exceptions)) {
                if (exception.isForArtifacts(artifacts, project.getArtifact())) {
                    result.add(exception);
                }
            }
        }
        return result;
    }

    private Set<Artifact> getArtifactsForElements(final Collection<File> elements, final Map<File, Optional<Artifact>> artifactsByFile)
    {
        final Set<Artifact> artifacts = new TreeSet<Artifact>();

        for (final File element : elements) {
            Optional<Artifact> artifact = artifactsByFile.get(element);
            checkState(artifact != null, "Could not find '%s' in the artifact cache!", element.getAbsolutePath());
            artifacts.add(artifact.or(project.getArtifact()));
        }
        return artifacts;
    }

    private String getArtifactsToString(final Collection<Artifact> artifacts)
    {
        final StringBuffer result = new StringBuffer();

        result.append("[");
        for (final Iterator<Artifact> it = artifacts.iterator(); it.hasNext();) {
            if (result.length() > 1) {
                result.append(",");
            }
            result.append(getQualifiedName(it.next()));
        }
        result.append("]");
        return result.toString();
    }

    private ClasspathDescriptor createClasspathDescriptor(final List<String> classpathElements, final Map<File, Optional<Artifact>> artifactsByFile) throws MojoExecutionException, InvalidVersionSpecificationException
    {
        final ClasspathDescriptor classpathDesc = new ClasspathDescriptor(useDefaultResourceIgnoreList, Arrays.asList(ignoredResources));
        final MatchArtifactPredicate matchArtifactPredicate = new MatchArtifactPredicate(ignoredDependencies);

        for (final String element : classpathElements) {

            try {
                File file = new File(element);
                if (file.exists()) {
                    Optional<Artifact> artifact = artifactsByFile.get(file);
                    checkState(artifact != null, "Could not find '%s' in the artifact cache!", file.getAbsolutePath());

                    // Add to the classpath if either no artifact exists (then it is an output folder) or the artifact
                    // predicate does not apply (then it is not in the ignoredDependencies list).
                    if (!artifact.isPresent() || !matchArtifactPredicate.apply(artifact.get())) {
                        classpathDesc.add(file);
                    }
                }
                else {
                    LOG.debug(format("Classpath element '%s' does not exist.", file.getAbsolutePath()));
                }
            }
            catch (final IOException ex) {
                throw new MojoExecutionException("Error trying to access element " + element, ex);
            }
        }
        return classpathDesc;
    }

    private ImmutableMap<File, Optional<Artifact>> createArtifactsByFileMap(final Iterable<Artifact> artifacts, File ... localFolders) throws DependencyResolutionRequiredException
    {
        final ImmutableMap.Builder<File, Optional<Artifact>> artifactsByFileBuilder = ImmutableMap.builder();

        for (final Artifact artifact : artifacts) {
            final File localPath = getLocalProjectPath(artifact);
            final File repoPath = artifact.getFile();

            if (localPath == null && repoPath == null) {
                throw new DependencyResolutionRequiredException(artifact);
            }
            if (localPath != null) {
                artifactsByFileBuilder.put(localPath, Optional.of(artifact));
            }
            if (repoPath != null) {
                artifactsByFileBuilder.put(repoPath, Optional.of(artifact));
            }
        }

        // Add local folders (source folder, test folder etc.)
        for (File localFolder : localFolders) {
            if (localFolder.exists()) {
                artifactsByFileBuilder.put(localFolder, Optional.<Artifact>absent());
            }
        }

        return artifactsByFileBuilder.build();
    }

    private File getLocalProjectPath(final Artifact artifact) throws DependencyResolutionRequiredException
    {
        final String refId = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
        final MavenProject owningProject = project.getProjectReferences().get(refId);

        if (owningProject != null) {
            if (artifact.getType().equals("test-jar")) {
                final File testOutputDir = new File(owningProject.getBuild().getTestOutputDirectory());

                if (testOutputDir.exists()) {
                    return testOutputDir;
                }
            }
            else {
                return new File(project.getBuild().getOutputDirectory());
            }
        }
        return null;
    }

    private File getOutputDirectory()
    {
        return new File(project.getBuild().getOutputDirectory());
    }

    private File getTestOutputDirectory()
    {
        return new File(project.getBuild().getTestOutputDirectory());
    }

    private String getQualifiedName(final Artifact artifact)
    {
        String result = artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();

        if (artifact.getType() != null && !"jar".equals(artifact.getType())) {
            result = result + ":" + artifact.getType();
        }
        if (artifact.getClassifier() != null && (!"tests".equals(artifact.getClassifier()) || !"test-jar".equals(artifact.getType()))) {
            result = result + ":" + artifact.getClassifier();
        }
        return result;
    }

    private void report(String formatString, Object ... args)
    {
        if (!quiet) {
            LOG.info(format(formatString, args));
        }
        else {
            LOG.debug(format(formatString, args));
        }
    }
}
