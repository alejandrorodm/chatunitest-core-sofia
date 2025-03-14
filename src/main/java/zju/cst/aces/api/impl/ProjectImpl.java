package zju.cst.aces.api.impl;

import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.shared.dependency.graph.DependencyGraphBuilder;
import org.apache.maven.shared.dependency.graph.DependencyNode;
import zju.cst.aces.api.Project;
import zju.cst.aces.parser.ProjectParser;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Implementation of the Project interface.
 */
public class ProjectImpl implements Project {

    MavenProject project;
    List<String> classPaths;

    /**
     * Constructs a ProjectImpl with the specified MavenProject.
     *
     * @param project the MavenProject instance
     */
    public ProjectImpl(MavenProject project) {
        this.project = project;
    }

    /**
     * Constructs a ProjectImpl with the specified MavenProject and class paths.
     *
     * @param project the MavenProject instance
     * @param classPaths the list of class paths
     */
    public ProjectImpl(MavenProject project, List<String> classPaths) {
        this.project = project;
        this.classPaths = classPaths;
    }

    /**
     * Returns the parent project.
     *
     * @return the parent project, or null if there is no parent
     */
    @Override
    public Project getParent() {
        if (project.getParent() == null) {
            return null;
        }
        return new ProjectImpl(project.getParent());
    }

    /**
     * Returns the base directory of the project.
     *
     * @return the base directory as a File
     */
    @Override
    public File getBasedir() {
        return project.getBasedir();
    }

    /**
     * Returns the packaging type of the project.
     *
     * @return the packaging type as a String
     */
    @Override
    public String getPackaging() {
        return project.getPackaging();
    }

    /**
     * Returns the group ID of the project.
     *
     * @return the group ID as a String
     */
    @Override
    public String getGroupId() {
        return project.getGroupId();
    }

    /**
     * Returns the artifact ID of the project.
     *
     * @return the artifact ID as a String
     */
    @Override
    public String getArtifactId() {
        return project.getArtifactId();
    }

    /**
     * Returns the compile source roots of the project.
     *
     * @return a list of compile source roots
     */
    @Override
    public List<String> getCompileSourceRoots() {
        return project.getCompileSourceRoots();
    }

    /**
     * Returns the path to the project's artifact.
     *
     * @return the artifact path as a Path
     */
    @Override
    public Path getArtifactPath() {
        return Paths.get(project.getBuild().getDirectory()).resolve(project.getBuild().getFinalName() + ".jar");
    }

    /**
     * Returns the build output directory path of the project.
     *
     * @return the build path as a Path
     */
    @Override
    public Path getBuildPath() {
        return Paths.get(project.getBuild().getOutputDirectory());
    }

    /**
     * Returns the class paths of the project.
     *
     * @return a list of class paths
     */
    @Override
    public List<String> getClassPaths() {
        return this.classPaths;
    }
}
