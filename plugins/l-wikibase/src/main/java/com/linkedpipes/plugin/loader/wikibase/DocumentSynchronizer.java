package com.linkedpipes.plugin.loader.wikibase;

import com.linkedpipes.etl.dataunit.core.rdf.WritableSingleGraphDataUnit;
import com.linkedpipes.etl.executor.api.v1.LpException;
import com.linkedpipes.etl.executor.api.v1.rdf.model.TripleWriter;
import com.linkedpipes.etl.executor.api.v1.service.ExceptionFactory;
import com.linkedpipes.plugin.loader.wikibase.model.GlobeCoordinateValue;
import com.linkedpipes.plugin.loader.wikibase.model.QuantityValue;
import com.linkedpipes.plugin.loader.wikibase.model.TimeValue;
import com.linkedpipes.plugin.loader.wikibase.model.WikibaseDocument;
import com.linkedpipes.plugin.loader.wikibase.model.WikibaseReference;
import com.linkedpipes.plugin.loader.wikibase.model.WikibaseStatement;
import com.linkedpipes.plugin.loader.wikibase.model.WikibaseValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.wdtk.datamodel.helpers.Datamodel;
import org.wikidata.wdtk.datamodel.helpers.ItemDocumentBuilder;
import org.wikidata.wdtk.datamodel.helpers.ReferenceBuilder;
import org.wikidata.wdtk.datamodel.helpers.StatementBuilder;
import org.wikidata.wdtk.datamodel.implementation.TermImpl;
import org.wikidata.wdtk.datamodel.interfaces.EntityDocument;
import org.wikidata.wdtk.datamodel.interfaces.ItemDocument;
import org.wikidata.wdtk.datamodel.interfaces.ItemIdValue;
import org.wikidata.wdtk.datamodel.interfaces.PropertyIdValue;
import org.wikidata.wdtk.datamodel.interfaces.Statement;
import org.wikidata.wdtk.datamodel.interfaces.StringValue;
import org.wikidata.wdtk.datamodel.interfaces.Value;
import org.wikidata.wdtk.wikibaseapi.ApiConnection;
import org.wikidata.wdtk.wikibaseapi.WikibaseDataEditor;
import org.wikidata.wdtk.wikibaseapi.WikibaseDataFetcher;
import org.wikidata.wdtk.wikibaseapi.apierrors.MediaWikiApiErrorException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

class DocumentSynchronizer {

    private static class StatementRef {

        private final String id;

        private final String predicate;

        private final String value;

        private String iri;

        private StatementRef(String id, String predicate, String value) {
            this.id = id;
            this.predicate = predicate;
            this.value = value;
            this.iri = null;
        }
    }

    private static final Logger LOG =
            LoggerFactory.getLogger(DocumentSynchronizer.class);

    private final ExceptionFactory exceptionFactory;

    private final WikibaseDataEditor wbde;

    private final WikibaseDataFetcher wbdf;

    private final TripleWriter reportOutput;

    private WikibaseDocument expectedState;

    private ItemDocument document;

    /**
     * Predicates are using siteIri given to WikibaseDataFetcher,
     * so we need to use the same IRI when we construct them.
     */
    private String siteIri;

    public DocumentSynchronizer(
            ExceptionFactory exceptionFactory,
            ApiConnection connection,
            String siteIri,
            WritableSingleGraphDataUnit output,
            int averageTimePerEdit) {
        this.siteIri = siteIri + "entity/";
        this.exceptionFactory = exceptionFactory;
        this.wbde = new WikibaseDataEditor(connection, this.siteIri);
        this.wbde.setAverageTimePerEdit(averageTimePerEdit);
        this.wbde.setEditAsBot(true);
        this.wbdf = new WikibaseDataFetcher(connection, this.siteIri);
        if (output == null) {
            this.reportOutput = null;
        } else {
            this.reportOutput = output.getWriter();
        }
    }

    public void synchronize(WikibaseDocument expectedState)
            throws LpException, MediaWikiApiErrorException, IOException {
        this.expectedState = expectedState;
        document = getDocumentFromWikidata(expectedState);
        synchronizeLabels();
        synchronizeNoValueStatements();
        synchronizeStatements();
        saveDocumentChanges();
        emitMapping();
        if (this.reportOutput != null) {
            this.reportOutput.flush();
        }
    }

    private ItemDocument getDocumentFromWikidata(
            WikibaseDocument expectedState)
            throws LpException, MediaWikiApiErrorException {
        if (expectedState.isNew()) {
            LOG.debug("New document created.");
            return ItemDocumentBuilder.forItemId(ItemIdValue.NULL).build();
        }
        EntityDocument document = wbdf.getEntityDocument(
                expectedState.getQid());
        if (document instanceof ItemDocument) {
            ItemDocument itemDocument = (ItemDocument) document;
            LOG.debug("Document: {} revision: {}",
                    itemDocument.getEntityId(),
                    document.getRevisionId());
            return (ItemDocument) document;
        } else {
            throw exceptionFactory.failure("Invalid document ({}) type: {}",
                    expectedState.getQid(), document.getClass().getName());
        }
    }

    private void synchronizeLabels() {
        expectedState.getLabels().forEach((lang, label) -> {
            // We do not use MonolingualTextValue as it does not serialize
            // well. It is designed as interface class for builders.
            TermImpl value = new TermImpl(lang, label);
            document = document.withLabel(value);
        });
    }

    private void synchronizeNoValueStatements() {
        for (String predicate : expectedState.getNoValuePredicates()) {
            PropertyIdValue property = createProperty(predicate);
            if (hasNoValueStatement(property)) {
                continue;
            }
            StatementBuilder builder =
                    StatementBuilder.forSubjectAndProperty(
                            document.getEntityId(), property);
            builder.withNoValue();
            document = document.withStatement(builder.build());
        }
    }

    private boolean hasNoValueStatement(PropertyIdValue property) {
        Iterator<Statement> statements = document.getAllStatements();
        while (statements.hasNext()) {
            Statement statement = statements.next();
            // TODO Check if there is no value property.
        }
        return false;
    }

    private void synchronizeStatements() {
        List<Statement> statementsToUpdate = new ArrayList<>();
        Set<String> toRemove = new HashSet<>();
        for (WikibaseStatement expected :
                expectedState.getStatements()) {
            if (expected.isNew()) {
                statementsToUpdate.add(createNewStatement(expected));
                continue;
            }
            Statement actual = getStatement(expected.getStatementId());
            if (actual == null) {
                LOG.info("Missing statement: {}", expected.getStatementId());
                continue;
            }
            toRemove.add(actual.getStatementId());
            if (expected.isForDelete()) {
                continue;
            }
            statementsToUpdate.add(synchronizeStatement(actual, expected));
        }
        document = document.withoutStatementIds(toRemove);
        for (Statement statement : statementsToUpdate) {
            document = document.withStatement(statement);
        }
    }

    private Statement createNewStatement(WikibaseStatement expected) {
        PropertyIdValue property = createProperty(expected);
        StatementBuilder builder = StatementBuilder
                .forSubjectAndProperty(document.getEntityId(), property);

        if (expected.getSimpleValue() != null) {
            StringValue value =
                    Datamodel.makeStringValue(expected.getSimpleValue());
            builder.withValue(value);
        }

        for (WikibaseValue value : expected.getQualifierValues()) {
            builder.withQualifierValue(property, createValue(value));
        }

        for (WikibaseValue value : expected.getStatementValues()) {
            builder.withValue(createValue(value));
        }

        for (WikibaseReference reference : expected.getReferences()) {
            ReferenceBuilder refBuilder = ReferenceBuilder.newInstance();
            for (WikibaseValue refValue : reference.getValues()) {
                // Reference values use same property as the reference.
                refBuilder.withPropertyValue(property, createValue(refValue));
            }
            builder.withReference(refBuilder.build());
        }

        return builder.build();
    }

    private PropertyIdValue createProperty(WikibaseStatement expected) {
        return createProperty(expected.getPredicate());
    }

    private PropertyIdValue createProperty(String predicate) {
        return Datamodel.makePropertyIdValue(predicate, siteIri);
    }

    private Value createValue(WikibaseValue value) {
        if (value instanceof QuantityValue) {
            QuantityValue quantity = (QuantityValue)value;
            return Datamodel.makeQuantityValue(
                    quantity.amount,
                    quantity.lowerBound, quantity.upperBound,
                    quantity.unit);
        } else if (value instanceof TimeValue) {
            TimeValue time = (TimeValue)value;
            return Datamodel.makeTimeValue(
                    time.year, time.month, time.day,
                    time.hour, time.minute, time.second,
                    time.precision,
                    0, 0,
                    time.timezone,
                    time.calendarModel);
        } else if (value instanceof GlobeCoordinateValue) {
            GlobeCoordinateValue globe = (GlobeCoordinateValue)value;
            return Datamodel.makeGlobeCoordinatesValue(
                    globe.latitude, globe.longitude,
                    globe.precision, globe.globe);
        } else {
            // TODO Throw exception.
            return null;
        }
    }

    private Statement getStatement(String statementId) {
        Iterator<Statement> statements = document.getAllStatements();
        while (statements.hasNext()) {
            Statement statement = statements.next();
            if (statement.getStatementId().equals(statementId)) {
                return statement;
            }
        }
        return null;
    }

    private Statement synchronizeStatement(
            Statement actual, WikibaseStatement expected) {
        PropertyIdValue property = createProperty(expected);
        StringValue value = Datamodel.makeStringValue(expected.getSimpleValue());
        return StatementBuilder.forSubjectAndProperty(
                actual.getSubject(), property)
                .withQualifiers(actual.getQualifiers())
                .withReferences(actual.getReferences())
                .withRank(actual.getRank())
                .withId(actual.getStatementId())
                .withValue(value)
                .build();
    }

    private void saveDocumentChanges()
            throws IOException, MediaWikiApiErrorException {
        if (expectedState.isNew()) {
            document = wbde.createItemDocument(document, "Create new entity.");
        } else {
            // We need to use replace here as we need to be able
            // to delete statements.
            document = wbde.editItemDocument(document, true, "Edit entity.");
        }
        LOG.debug("Saving document: {} revision: {}",
                document.getEntityId(),
                document.getRevisionId());
    }

    private void emitMapping() {
        if (this.reportOutput == null) {
            return;
        }
        Map<String, String> mapping;
        if (expectedState.isNew()) {
            mapping = collectMappingNewDocument();
        } else {
            mapping = collectMappingUpdate();
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            reportOutput.iri(
                    entry.getKey(),
                    WikibaseLoaderVocabulary.WIKIDATA_MAPPING,
                    entry.getValue());
        }
    }

    private Map<String, String> collectMappingNewDocument() {
        Map<String, String> result = collectMappingUpdate();
        result.put(expectedState.getIri(), document.getEntityId().getIri());
        return result;
    }

    private Map<String, String> collectMappingUpdate() {
        List<StatementRef> statementRefs = collectStatementRefs(document);
        mapRefsToExpectedState(statementRefs);
        Map<String, String> results = new HashMap<>();
        for (StatementRef newRef : statementRefs) {
            if (newRef.iri == null) {
                continue;
            }
            results.put(newRef.iri,
                    siteIri + "statement/" + newRef.id.replace("$", "-"));
        }
        return results;
    }

    private List<StatementRef> collectStatementRefs(ItemDocument document) {
        List<StatementRef> results = new ArrayList<>();
        document.getStatementGroups().forEach((group) -> {
            group.getStatements().forEach((statement) -> {
                String value = statement.getValue().toString();
                if (value.startsWith("\"") && value.endsWith("\"")) {
                    value = value.substring(1, value.length() - 1);
                }
                results.add(new StatementRef(
                        statement.getStatementId(),
                        group.getProperty().getId(),
                        value));
            });
        });
        return results;
    }

    private void mapRefsToExpectedState(List<StatementRef> refs) {
        for (WikibaseStatement expected :
                expectedState.getStatements()) {
            if (expected.isForDelete()) {
                continue;
            }
            if (!expected.isNew()) {
                // Skip updated as the ID is preserved.
                continue;
            }
            for (StatementRef ref : refs) {
                if (!ref.predicate.equals(expected.getPredicate())) {
                    continue;
                }
                if (!ref.value.equals(expected.getSimpleValue())) {
                    continue;
                }
                ref.iri = expected.getIri();
                break;
            }
        }
    }

}