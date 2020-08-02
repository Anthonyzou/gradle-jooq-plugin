/*
 Copyright 2014 Etienne Studer

 Licensed under the Apache License, Version 2.0 (the "License");
 you may not use this file except in compliance with the License.
 You may obtain a copy of the License at

 http://www.apache.org/licenses/LICENSE-2.0

 Unless required by applicable law or agreed to in writing, software
 distributed under the License is distributed on an "AS IS" BASIS,
 WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 See the License for the specific language governing permissions and
 limitations under the License.
 */
package nu.studer.gradle.jooq;

import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.ProjectLayout;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.api.tasks.TaskExecutionException;
import org.gradle.process.ExecOperations;
import org.gradle.process.ExecResult;
import org.gradle.process.JavaExecSpec;
import org.jooq.Constants;
import org.jooq.codegen.GenerationTool;
import org.jooq.meta.jaxb.Configuration;
import org.jooq.meta.jaxb.Generator;
import org.jooq.meta.jaxb.Target;
import org.xml.sax.SAXException;

import javax.inject.Inject;
import javax.xml.XMLConstants;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static nu.studer.gradle.jooq.util.Objects.cloneObject;

/**
 * Gradle Task that runs the jOOQ source code generation.
 */
public class JooqGenerate extends DefaultTask {

    private final JooqConfig config;
    private final ConfigurableFileCollection runtimeClasspath;
    private Configuration normalizedConfiguration;
    private Action<? super Configuration> generationToolNormalization;
    private Action<? super JavaExecSpec> javaExecSpec;
    private Action<? super ExecResult> execResultHandler;

    private final ProjectLayout projectLayout;
    private final ExecOperations execOperations;

    @Inject
    public JooqGenerate(JooqConfig config, FileCollection runtimeClasspath, ObjectFactory objects, ProjectLayout projectLayout, ExecOperations execOperations) {
        this.config = config;
        this.runtimeClasspath = objects.fileCollection().from(runtimeClasspath);

        this.projectLayout = projectLayout;
        this.execOperations = execOperations;
    }

    @SuppressWarnings("unused")
    @Nested
    public JooqConfig getConfig() {
        return config;
    }

    @SuppressWarnings("unused")
    @Classpath
    public ConfigurableFileCollection getRuntimeClasspath() {
        return runtimeClasspath;
    }

    @Input
    public Configuration getNormalizedConfiguration() {
        normalizeConfiguration();
        return normalizedConfiguration;
    }

    @OutputDirectory
    public Provider<Directory> getOutputDir() {
        return config.getOutputDir();
    }

    @Internal
    public Action<? super JavaExecSpec> getJavaExecSpec() {
        return javaExecSpec;
    }

    public void setJavaExecSpec(Action<? super JavaExecSpec> javaExecSpec) {
        this.javaExecSpec = javaExecSpec;
    }

    @Internal
    public Action<? super ExecResult> getExecResultHandler() {
        return execResultHandler;
    }

    public void setExecResultHandler(Action<? super ExecResult> execResultHandler) {
        this.execResultHandler = execResultHandler;
    }

    @Internal
    public Action<? super Configuration> getGenerationToolNormalization() {
        return generationToolNormalization;
    }

    public void setGenerationToolNormalization(Action<? super Configuration> generationToolNormalization) {
        this.generationToolNormalization = generationToolNormalization;
    }

    private void normalizeConfiguration() {
        if (normalizedConfiguration == null) {
            if (generationToolNormalization != null) {
                Configuration clonedConfiguration = cloneObject(config.getJooqConfiguration());
                generationToolNormalization.execute(clonedConfiguration);
                normalizedConfiguration = clonedConfiguration;
            } else {
                normalizedConfiguration = config.getJooqConfiguration();
            }
        }
    }

    @TaskAction
    public void generate() {
        // define a config file to which the jOOQ code generation configuration is written to
        File configFile = new File(getTemporaryDir(), "config.xml");

        // set target directory
        Target target = ensureTargetIsPresent(config.getJooqConfiguration());
        target.setDirectory(config.getOutputDir().get().getAsFile().getAbsolutePath());

        // write jOOQ code generation configuration to config file
        writeConfiguration(config.getJooqConfiguration(), configFile);

        // generate the jOOQ Java sources files using the written config file
        ExecResult execResult = executeJooq(configFile);

        // invoke custom result handler
        if (execResultHandler != null) {
            execResultHandler.execute(execResult);
        }
    }

    private Target ensureTargetIsPresent(Configuration configuration) {
        if (configuration.getGenerator() == null) {
            configuration.withGenerator(new Generator().withTarget(new Target()));
        } else if (configuration.getGenerator().getTarget() == null) {
            configuration.getGenerator().withTarget(new Target());
        }

        if (configuration.getGenerator().getTarget().getDirectory() != null) {
            throw new InvalidUserDataException("Target directory must be set via jooq { " + config.name + " { outputDir = <target directory> } }");
        }

        return configuration.getGenerator().getTarget();
    }

    private void writeConfiguration(Configuration configuration, File targetFile) {
        try (OutputStream fs = new FileOutputStream(targetFile)) {
            SchemaFactory sf = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = sf.newSchema(GenerationTool.class.getResource("/xsd/" + Constants.XSD_CODEGEN));

            JAXBContext ctx = JAXBContext.newInstance(Configuration.class);
            Marshaller marshaller = ctx.createMarshaller();
            marshaller.setSchema(schema);

            marshaller.marshal(configuration, fs);
        } catch (IOException | JAXBException | SAXException e) {
            throw new TaskExecutionException(JooqGenerate.this, e);
        }
    }

    private ExecResult executeJooq(File configurationFile) {
        return execOperations.javaexec(spec -> {
            spec.setMain("org.jooq.codegen.GenerationTool");
            spec.setClasspath(runtimeClasspath);
            spec.setWorkingDir(projectLayout.getProjectDirectory());
            spec.args(configurationFile);

            if (javaExecSpec != null) {
                javaExecSpec.execute(spec);
            }
        });
    }

}
