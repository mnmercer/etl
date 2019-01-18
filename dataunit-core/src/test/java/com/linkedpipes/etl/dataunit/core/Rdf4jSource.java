package com.linkedpipes.etl.dataunit.core;

import com.linkedpipes.etl.executor.api.v1.rdf.RdfException;
import com.linkedpipes.etl.executor.api.v1.rdf.model.RdfSource;
import com.linkedpipes.etl.executor.api.v1.rdf.model.RdfValue;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

import java.io.File;
import java.io.FileInputStream;
import java.net.URL;
import java.util.List;
import java.util.stream.Collectors;

public class Rdf4jSource implements RdfSource {

    private class Rdf4jValue implements RdfValue {

        private Value value;

        public Rdf4jValue(Value value) {
            this.value = value;
        }

        @Override
        public String asString() {
            return value.stringValue();
        }

        @Override
        public String getLanguage() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getType() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Boolean asBoolean() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Long asLong() {
            throw new UnsupportedOperationException();
        }

    }

    private Model model;

    public void loadFile(File file) throws Exception {
        RDFFormat format = Rio.getParserFormatForFileName(file.getName()).get();
        try (FileInputStream stream = new FileInputStream(file)) {
            model = Rio.parse(stream, "http://localhost", format);
        }
    }

    public void loadTestResource(String resource) throws Exception {
        loadFile(fileFromResource(resource));
    }

    public File fileFromResource(String fileName) {
        URL url = Thread.currentThread().getContextClassLoader()
                .getResource(fileName);
        if (url == null) {
            throw new RuntimeException(
                    "Required resource '" + fileName + "' is missing.");
        }
        return new File(url.getPath());
    }

    @Override
    public List<String> getByType(String type) {
        ValueFactory valueFactory = SimpleValueFactory.getInstance();
        return model.filter(null, RDF.TYPE, valueFactory.createIRI(type))
                .stream()
                .map((statement -> statement.getSubject().stringValue()))
                .collect(Collectors.toList());
    }

    @Override
    public List<RdfValue> getPropertyValues(String subject, String predicate) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void statements(String subject, StatementHandler handler)
            throws RdfException {
        for (Statement statement : model) {
            if (!statement.getSubject().stringValue().equals(subject)) {
                continue;
            }
            handler.accept(statement.getPredicate().stringValue(),
                    new Rdf4jValue(statement.getObject()));
        }
    }

}
