/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt 
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *  
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.recommendation.api.model;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableSet;
import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.toList;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;

import org.apache.commons.lang3.Validate;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationDocument;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.LogMessage;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.rendering.vmodel.VID;

/**
 * If the prediction task has run it stores the predicted annotations for an annotation layer in the
 * predictions map.
 */
public class Predictions
    implements Serializable
{
    private static final long serialVersionUID = -1598768729246662885L;

    private final int generation;
    private final Project project;
    private final User sessionOwner;
    private final String dataOwner;

    private final Map<String, Map<ExtendedId, AnnotationSuggestion>> idxDocuments = new HashMap<>();

    private final Object predictionsLock = new Object();
    private final Set<String> seenDocumentsForPrediction = new HashSet<>();
    private final List<LogMessage> log = new ArrayList<>();

    // Predictions are (currently) scoped to a user session. We assume that within a single user
    // session, the pool of IDs of positive integer values is never exhausted.
    private int nextId;

    private int newSuggestionCount = 0;

    public Predictions(User aSessionOwner, String aDataOwner, Project aProject)
    {
        Validate.notNull(aProject, "Project must be specified");
        Validate.notNull(aSessionOwner, "Session owner must be specified");
        Validate.notNull(aDataOwner, "Data owner must be specified");

        project = aProject;
        sessionOwner = aSessionOwner;
        dataOwner = aDataOwner;
        nextId = 0;
        generation = 1;
    }

    public Predictions(Predictions aPredecessor)
    {
        project = aPredecessor.project;
        sessionOwner = aPredecessor.sessionOwner;
        dataOwner = aPredecessor.dataOwner;
        nextId = aPredecessor.nextId;
        generation = aPredecessor.generation + 1;
    }

    public User getSessionOwner()
    {
        return sessionOwner;
    }

    public String getDataOwner()
    {
        return dataOwner;
    }

    /**
     * @param type
     *            the suggestion type
     * @param aLayer
     *            the layer
     * @param aDocumentService
     *            the document service for obtaining documents
     * @param <T>
     *            the suggestion type
     * @return the predictions of a given window for each document, where the outer list is a list
     *         of tokens and the inner list is a list of predictions for a token. The method filters
     *         all tokens which already have an annotation and don't need further recommendation.
     */
    public <T extends AnnotationSuggestion> Map<String, SuggestionDocumentGroup<T>> getPredictionsForWholeProject(
            Class<T> type, AnnotationLayer aLayer, DocumentService aDocumentService)
    {
        var result = new HashMap<String, SuggestionDocumentGroup<T>>();

        var docs = aDocumentService.listAnnotationDocuments(project, sessionOwner);

        for (AnnotationDocument doc : docs) {
            // TODO #176 use the document Id once it it available in the CAS
            var p = getGroupedPredictions(type, doc.getName(), aLayer, -1, -1);
            result.put(doc.getName(), p);
        }

        return result;
    }

    /**
     * TODO #176 use the document Id once it it available in the CAS
     * 
     * @param type
     *            the type of suggestions to retrieve
     * @param aDocumentName
     *            the name of the document to retrieve suggestions for
     * @param aLayer
     *            the layer to retrieve suggestions for
     * @param aWindowBegin
     *            the begin of the window for which to retrieve suggestions
     * @param aWindowEnd
     *            the end of the window for which to retrieve suggestions
     * @param <T>
     *            the suggestion type
     * 
     * @return the predictions of a given window, where the outer list is a list of tokens and the
     *         inner list is a list of predictions for a token
     */
    public <T extends AnnotationSuggestion> SuggestionDocumentGroup<T> getGroupedPredictions(
            Class<T> type, String aDocumentName, AnnotationLayer aLayer, int aWindowBegin,
            int aWindowEnd)
    {
        return new SuggestionDocumentGroup<>(
                getFlattenedPredictions(type, aDocumentName, aLayer, aWindowBegin, aWindowEnd));
    }

    /**
     * TODO #176 use the document Id once it it available in the CAS
     * 
     * Get the predictions of a document for a given window in a flattened list. If the parameters
     * {@code aWindowBegin} and {@code aWindowEnd} are {@code -1}, then they are ignored
     * respectively. This is useful when all suggestions should be fetched.
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    private <T extends AnnotationSuggestion> List<T> getFlattenedPredictions(Class<T> type,
            String aDocumentName, AnnotationLayer aLayer, int aWindowBegin, int aWindowEnd)
    {
        synchronized (predictionsLock) {
            var byDocument = idxDocuments.getOrDefault(aDocumentName, emptyMap());
            return byDocument.entrySet().stream() //
                    .filter(f -> type.isInstance(f.getValue())) //
                    .map(f -> (Entry<ExtendedId, T>) (Entry) f) //
                    .filter(f -> f.getKey().getLayerId() == aLayer.getId()) //
                    // .filter(f -> overlapping(f.getValue().getWindowBegin(),
                    // f.getValue().getWindowEnd(),
                    // aWindowBegin == -1 ? 0 : aWindowBegin,
                    // aWindowEnd == -1 ? MAX_VALUE : aWindowEnd))
                    .filter(f -> aWindowBegin == -1
                            || (f.getValue().getWindowBegin() >= aWindowBegin))
                    .filter(f -> aWindowEnd == -1 || (f.getValue().getWindowEnd() <= aWindowEnd))
                    .sorted(comparingInt(e2 -> e2.getValue().getWindowBegin())) //
                    .map(Map.Entry::getValue) //
                    .collect(toList());
        }
    }

    /**
     * @param aDocument
     *            the source document
     * @param aVID
     *            the annotation ID
     * @return the first prediction that matches recommendationId and recommenderId in the given
     *         document.
     */
    public Optional<AnnotationSuggestion> getPredictionByVID(SourceDocument aDocument, VID aVID)
    {
        synchronized (predictionsLock) {
            var byDocument = idxDocuments.getOrDefault(aDocument.getName(), emptyMap());
            return byDocument.values().stream() //
                    .filter(suggestion -> suggestion.getId() == aVID.getSubId()) //
                    .filter(suggestion -> suggestion.getRecommenderId() == aVID.getId()) //
                    .findFirst();
        }
    }

    /**
     * @param aPredictions
     *            list of sentences containing recommendations
     */
    public void putPredictions(List<AnnotationSuggestion> aPredictions)
    {
        synchronized (predictionsLock) {
            for (var prediction : aPredictions) {
                // Assign ID to predictions that do not have an ID yet
                if (prediction.getId() == AnnotationSuggestion.NEW_ID) {
                    prediction = prediction.assignId(nextId);
                    nextId++;
                    if (nextId < 0) {
                        throw new IllegalStateException(
                                "Annotation suggestion ID overflow. Restart session.");
                    }
                }

                var xid = new ExtendedId(prediction);
                var byDocument = idxDocuments.computeIfAbsent(prediction.getDocumentName(),
                        $ -> new HashMap<>());
                byDocument.put(xid, prediction);

                if (prediction.getAge() == 0) {
                    newSuggestionCount++;
                }
            }
        }
    }

    public Project getProject()
    {
        return project;
    }

    public boolean isEmpty()
    {
        synchronized (predictionsLock) {
            return idxDocuments.values().stream().allMatch(Map::isEmpty);
        }
    }

    public boolean hasNewSuggestions()
    {
        return newSuggestionCount > 0;
    }

    public int getNewSuggestionCount()
    {
        return newSuggestionCount;
    }

    public int size()
    {
        synchronized (predictionsLock) {
            return idxDocuments.values().stream().mapToInt(Map::size).sum();
        }
    }

    public void removePredictions(Long recommenderId)
    {
        synchronized (predictionsLock) {
            idxDocuments.values().forEach(docGroup -> docGroup.entrySet() //
                    .removeIf((p) -> p.getKey().getRecommenderId() == recommenderId));
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<SpanSuggestion> getAlternativeSuggestions(SpanSuggestion aSuggestion)
    {
        synchronized (predictionsLock) {
            var byDocument = idxDocuments.getOrDefault(aSuggestion.getDocumentName(), emptyMap());
            return byDocument.entrySet().stream() //
                    .filter(f -> f.getValue() instanceof SpanSuggestion) //
                    .map(f -> (Entry<ExtendedId, SpanSuggestion>) (Entry) f) //
                    .filter(f -> f.getKey().getLayerId() == aSuggestion.getLayerId()) //
                    .filter(f -> f.getValue().getBegin() == aSuggestion.getBegin()) //
                    .filter(f -> f.getValue().getEnd() == aSuggestion.getEnd()) //
                    .filter(f -> f.getValue().getFeature().equals(aSuggestion.getFeature())) //
                    .map(Map.Entry::getValue) //
                    .collect(toList());
        }
    }

    /**
     * TODO #176 use the document Id once it it available in the CAS Returns a list of predictions
     * for a given token that matches the given layer and the annotation feature in the given
     * document
     *
     * @param aDocumentName
     *            the given document name
     * @param aLayer
     *            the given layer
     * @param aBegin
     *            the offset character begin
     * @param aEnd
     *            the offset character end
     * @param aFeature
     *            the given annotation feature name
     * @return the annotation suggestions
     */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public List<SpanSuggestion> getPredictionsByTokenAndFeature(String aDocumentName,
            AnnotationLayer aLayer, int aBegin, int aEnd, String aFeature)
    {
        synchronized (predictionsLock) {
            var byDocument = idxDocuments.getOrDefault(aDocumentName, emptyMap());
            return byDocument.entrySet().stream() //
                    .filter(f -> f.getValue() instanceof SpanSuggestion) //
                    .map(f -> (Entry<ExtendedId, SpanSuggestion>) (Entry) f) //
                    .filter(f -> f.getKey().getLayerId() == aLayer.getId()) //
                    .filter(f -> f.getValue().getBegin() == aBegin) //
                    .filter(f -> f.getValue().getEnd() == aEnd) //
                    .filter(f -> f.getValue().getFeature().equals(aFeature)) //
                    .map(Map.Entry::getValue) //
                    .collect(toList());
        }
    }

    public List<AnnotationSuggestion> getPredictionsByRecommenderAndDocument(
            Recommender aRecommender, String aDocumentName)
    {
        synchronized (predictionsLock) {
            var byDocument = idxDocuments.getOrDefault(aDocumentName, emptyMap());
            return byDocument.entrySet().stream() //
                    .filter(f -> f.getKey().getRecommenderId() == (long) aRecommender.getId())
                    .map(Map.Entry::getValue) //
                    .collect(toList());
        }
    }

    public List<AnnotationSuggestion> getPredictionsByDocument(String aDocumentName)
    {
        synchronized (predictionsLock) {
            var byDocument = idxDocuments.getOrDefault(aDocumentName, emptyMap());
            return byDocument.entrySet().stream() //
                    .map(Map.Entry::getValue) //
                    .collect(toList());
        }
    }

    public void markDocumentAsPredictionCompleted(SourceDocument aDocument)
    {
        synchronized (seenDocumentsForPrediction) {
            seenDocumentsForPrediction.add(aDocument.getName());
        }
    }

    Set<String> documentsSeen()
    {
        return unmodifiableSet(seenDocumentsForPrediction);
    }

    public boolean hasRunPredictionOnDocument(SourceDocument aDocument)
    {
        synchronized (seenDocumentsForPrediction) {
            return seenDocumentsForPrediction.contains(aDocument.getName());
        }
    }

    public void log(LogMessage aMessage)
    {
        synchronized (log) {
            log.add(aMessage);
        }
    }

    public void inheritLog(List<LogMessage> aLogMessages)
    {
        synchronized (log) {
            log.addAll(0, aLogMessages);
        }
    }

    public int getGeneration()
    {
        return generation;
    }

    public List<LogMessage> getLog()
    {
        synchronized (log) {
            // Making a copy here because we may still write to the log and don't want to hand out
            // a live copy... which might cause problems, e.g. if the live copy would be used in the
            // Wicket UI and becomes subject to serialization.
            return asList(log.stream().toArray(LogMessage[]::new));
        }
    }
}
