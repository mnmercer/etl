package com.linkedpipes.plugin.transformer.jsonldformattitanium;

import com.github.jsonldjava.utils.JsonUtils;
import com.linkedpipes.etl.dataunit.core.files.FilesDataUnit;
import com.linkedpipes.etl.dataunit.core.files.WritableFilesDataUnit;
import com.linkedpipes.etl.dataunit.core.rdf.SingleGraphDataUnit;
import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.executor.api.v1.component.Component;
import com.linkedpipes.etl.executor.api.v1.component.SequentialExecution;
import com.linkedpipes.etl.executor.api.v1.service.ProgressReport;

import java.io.File;
import java.io.IOException;

public class JsonLdFormatTitanium implements Component, SequentialExecution {

    @Component.ContainsConfiguration
    @Component.InputPort(iri = "Configuration")
    public SingleGraphDataUnit configurationRdf;

    @Component.InputPort(iri = "InputFiles")
    public FilesDataUnit inputFiles;

    @Component.InputPort(iri = "OutputFiles")
    public WritableFilesDataUnit outputFiles;

    @Component.Configuration
    public JsonLdFormatTitaniumConfiguration configuration;

    @Component.Inject
    public ProgressReport progressReport;

    private Object context;

    @Override
    public void execute() throws LpException {
        initializeContext();
        progressReport.start(inputFiles.size());
        for (FilesDataUnit.Entry entry : inputFiles) {
            File inputFIle = entry.toFile();
            File outputFile = outputFiles.createFile(entry.getFileName());
            try {
                transformFile(inputFIle, outputFile);
            } catch (LpException ex) {
                throw new LpException(
                        "Can't transform: {}", entry.getFileName(), ex);
            }
            progressReport.entryProcessed();
        }
        progressReport.done();
    }

    private void initializeContext() throws LpException {
        if (JsonLdFormatTitaniumVocabulary.EXPANDED.equals(
                configuration.getFormat())) {
            // Expanded mode does not use context.
            return;
        }
        try {
            context = JsonUtils.fromString(configuration.getContext());
        } catch (IOException ex) {
            throw new LpException("Can't prepare context.", ex);
        }
    }

    private void transformFile(File source, File target) throws LpException {
        TitaniumOperator operator = new TitaniumOperator();
        switch (configuration.getFormat()) {
            case JsonLdFormatTitaniumVocabulary.COMPACT:
                operator.compact(source, configuration.getContext(), target);
                break;
            case JsonLdFormatTitaniumVocabulary.FLAT:
                operator.flatten(source, target);
                break;
            case JsonLdFormatTitaniumVocabulary.EXPANDED:
                operator.expand(source, target);
                break;
            case JsonLdFormatTitaniumVocabulary.FRAME:
                operator.frame(
                        source, configuration.getFrame(), target);
                break;
            case JsonLdFormatTitaniumVocabulary.FRAME_AS_ARRAY:
                operator.frameAsArray(
                        source,
                        configuration.getFrame(),
                        configuration.getContext(),
                        target);
                break;
            default:
                throw new LpException("Invalid format type: '{}'",
                        configuration.getFormat());
        }
    }

}
