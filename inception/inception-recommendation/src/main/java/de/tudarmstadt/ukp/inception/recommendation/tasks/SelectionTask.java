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
package de.tudarmstadt.ukp.inception.recommendation.tasks;

import static de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasAccessMode.SHARED_READ_ONLY_ACCESS;
import static de.tudarmstadt.ukp.clarin.webanno.api.casstorage.CasUpgradeMode.AUTO_CAS_UPGRADE;
import static java.lang.System.currentTimeMillis;
import static java.text.MessageFormat.format;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import javax.persistence.NoResultException;

import org.apache.commons.lang3.concurrent.ConcurrentException;
import org.apache.commons.lang3.concurrent.LazyInitializer;
import org.apache.uima.cas.CAS;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.clarin.webanno.security.model.User;
import de.tudarmstadt.ukp.clarin.webanno.support.WebAnnoConst;
import de.tudarmstadt.ukp.clarin.webanno.support.logging.LogMessage;
import de.tudarmstadt.ukp.inception.annotation.storage.CasStorageSession;
import de.tudarmstadt.ukp.inception.documents.api.DocumentService;
import de.tudarmstadt.ukp.inception.recommendation.api.RecommendationService;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.EvaluationResult;
import de.tudarmstadt.ukp.inception.recommendation.api.evaluation.PercentageBasedSplitter;
import de.tudarmstadt.ukp.inception.recommendation.api.model.EvaluatedRecommender;
import de.tudarmstadt.ukp.inception.recommendation.api.model.Recommender;
import de.tudarmstadt.ukp.inception.recommendation.api.recommender.RecommendationException;
import de.tudarmstadt.ukp.inception.recommendation.event.RecommenderEvaluationResultEvent;
import de.tudarmstadt.ukp.inception.recommendation.event.RecommenderTaskNotificationEvent;
import de.tudarmstadt.ukp.inception.scheduling.SchedulingService;
import de.tudarmstadt.ukp.inception.schema.api.AnnotationSchemaService;

/**
 * This task evaluates all available classification tools for all annotation layers of the current
 * project. If a classifier exceeds its specific activation score limit during the evaluation it is
 * selected for active prediction.
 * 
 * If the threshold is 0 (or less), the evaluation should be considered optional. That is, if the
 * evaluation fails (e.g. because of too little data), then the training should still be scheduled.
 */
public class SelectionTask
    extends RecommendationTask_ImplBase
{
    private final Logger log = LoggerFactory.getLogger(getClass());

    private @Autowired AnnotationSchemaService annoService;
    private @Autowired DocumentService documentService;
    private @Autowired RecommendationService recommendationService;
    private @Autowired ApplicationEventPublisher appEventPublisher;
    private @Autowired SchedulingService schedulingService;

    private final SourceDocument currentDocument;
    private final String dataOwner;

    /**
     * Create a new selection task.
     * 
     * @param aUser
     *            the user owning the selection session.
     * @param aProject
     *            the project to perform the selection on.
     * @param aTrigger
     *            the trigger that caused the selection to be scheduled.
     * @param aCurrentDocument
     *            the document currently open in the editor.
     * @param aDataOwner
     *            the user owning the annotations currently shown in the editor (this can differ
     *            from the user owning the session e.g. if a manager views another users annotations
     *            or a curator is performing curation to the {@link WebAnnoConst#CURATION_USER})
     */
    public SelectionTask(User aUser, Project aProject, String aTrigger,
            SourceDocument aCurrentDocument, String aDataOwner)
    {
        super(aUser, aProject, aTrigger);

        if (getUser().isEmpty()) {
            throw new IllegalArgumentException("SelectionTask requires a user");
        }

        currentDocument = aCurrentDocument;
        dataOwner = aDataOwner;
    }

    @Override
    public String getTitle()
    {
        return "Activating trainable recommenders...";
    }

    @Override
    public void execute()
    {
        try (CasStorageSession session = CasStorageSession.open()) {
            var sessionOwner = getUser().orElseThrow();
            var sessionOwnerName = sessionOwner.getUsername();
            var startTime = System.currentTimeMillis();
            var project = getProject();

            // Read the CASes only when they are accessed the first time. This allows us to skip
            // reading the CASes in case that no layer / recommender is available or if no
            // recommender requires evaluation.
            var casLoader = new LazyInitializer<List<CAS>>()
            {
                @Override
                protected List<CAS> initialize()
                {
                    return readCasses(project, dataOwner);
                }
            };

            var listAnnotationLayers = annoService.listAnnotationLayer(getProject());
            getMonitor().setMaxProgress(listAnnotationLayers.size());
            boolean seenRecommender = false;
            var layers = annoService.listAnnotationLayer(getProject());
            for (AnnotationLayer layer : layers) {
                getMonitor().incrementProgress();

                if (!layer.isEnabled()) {
                    continue;
                }

                var recommenders = recommendationService.listRecommenders(layer);
                if (recommenders == null || recommenders.isEmpty()) {
                    logNoRecommenders(sessionOwnerName, layer);
                    continue;
                }

                var evaluatedRecommenders = new ArrayList<EvaluatedRecommender>();
                for (Recommender r : recommenders) {
                    // Make sure we have the latest recommender config from the DB - the one from
                    // the active recommenders list may be outdated
                    var optRecommender = freshenRecommender(sessionOwner, r);
                    if (optRecommender.isEmpty()) {
                        logRecommenderGone(sessionOwner, r);
                        continue;
                    }

                    if (!seenRecommender) {
                        logSelectionStarted(sessionOwner);
                        seenRecommender = true;
                    }

                    Recommender recommender = optRecommender.get();
                    try {
                        long start = System.currentTimeMillis();

                        getMonitor().addMessage(LogMessage.info(this, "%s", recommender.getName()));
                        evaluate(sessionOwner, recommender, casLoader)
                                .ifPresent(evaluatedRecommender -> {
                                    var result = evaluatedRecommender.getEvaluationResult();

                                    evaluatedRecommenders.add(evaluatedRecommender);
                                    appEventPublisher
                                            .publishEvent(new RecommenderEvaluationResultEvent(this,
                                                    recommender, sessionOwner.getUsername(), result,
                                                    currentTimeMillis() - start,
                                                    evaluatedRecommender.isActive()));
                                });
                    }

                    // Catching Throwable is intentional here as we want to continue the execution
                    // even if a particular recommender fails.
                    catch (Throwable e) {
                        logEvaluationFailed(project, sessionOwner, recommender.getName(), e);
                    }
                }

                recommendationService.setEvaluatedRecommenders(sessionOwner, layer,
                        evaluatedRecommenders);

                logEvaluationSuccessful(sessionOwner);
            }

            if (!seenRecommender) {
                logNoRecommendersSeen(sessionOwnerName);
                return;
            }

            if (!recommendationService.hasActiveRecommenders(sessionOwner.getUsername(), project)) {
                logNoRecommendersActive(sessionOwnerName);
                return;
            }

            logSelectionComplete(startTime, sessionOwnerName);

            scheduleTrainingTask(sessionOwner);
        }
    }

    private void logSelectionComplete(long startTime, String username)
    {
        var duration = currentTimeMillis() - startTime;
        log.debug("[{}][{}]: Selection complete ({} ms)", getId(), username, duration);
        info("Selection complete (%d ms).", duration);
    }

    private void logRecommenderGone(User user, Recommender aRecommender)
    {
        log.debug("[{}][{}][{}]: Recommender no longer available... skipping", getId(),
                user.getUsername(), aRecommender.getName());
    }

    private void logSelectionStarted(User sessionOwner)
    {
        log.info("[{}]: Starting selection triggered by [{}]", sessionOwner.getUsername(),
                getTrigger());
        info("Starting selection triggered by [%s]", getTrigger());
    }

    private void scheduleTrainingTask(User sessionOwner)
    {
        TrainingTask trainingTask = new TrainingTask(sessionOwner, getProject(),
                "SelectionTask after activating recommenders", currentDocument, dataOwner);
        trainingTask.inheritLog(this);
        schedulingService.enqueue(trainingTask);
    }

    private void logNoRecommendersActive(String sessionOwnerName)
    {
        log.debug("[{}]: No recommenders active, skipping training.", sessionOwnerName);
    }

    private void logNoRecommendersSeen(String sessionOwnerName)
    {
        log.trace("[{}]: No recommenders configured, skipping training.", sessionOwnerName);
    }

    private void logEvaluationSuccessful(User sessionOwner)
    {
        log.info("[{}]: Evaluation complete", sessionOwner.getUsername());
        appEventPublisher.publishEvent(RecommenderTaskNotificationEvent
                .builder(this, getProject(), sessionOwner.getUsername()) //
                .withMessage(LogMessage.info(this, "Evaluation complete")) //
                .build());
    }

    private void logEvaluationFailed(Project project, User sessionOwner, String recommenderName,
            Throwable e)
    {
        log.error("[{}][{}]: Evaluation failed", sessionOwner.getUsername(), recommenderName, e);
        appEventPublisher.publishEvent(
                RecommenderTaskNotificationEvent.builder(this, project, sessionOwner.getUsername()) //
                        .withMessage(LogMessage.error(this, e.getMessage())) //
                        .build());
    }

    private void logNoRecommenders(String sessionOwnerName, AnnotationLayer layer)
    {
        log.trace("[{}][{}]: No recommenders, skipping selection.", sessionOwnerName,
                layer.getUiName());
    }

    private Optional<EvaluatedRecommender> evaluate(User user, Recommender recommender,
            LazyInitializer<List<CAS>> aCasses)
        throws RecommendationException, ConcurrentException
    {
        var userName = user.getUsername();

        var optFactory = recommendationService.getRecommenderFactory(recommender);
        if (optFactory.isEmpty()) {
            sendMissingFactoryNotification(user, recommender);
            return Optional.empty();
        }

        var factory = optFactory.get();
        if (!factory.accepts(recommender.getLayer(), recommender.getFeature())) {
            return Optional.of(skipRecommenderWithInvalidSettings(user, recommender));
        }

        if (recommender.isAlwaysSelected()) {
            return Optional.of(activateAlwaysOnRecommender(userName, recommender));
        }

        if (!factory.isEvaluable()) {
            return Optional.of(activateNonEvaluatableRecommender(userName, recommender));
        }

        log.info("[{}][{}]: Evaluating...", userName, recommender.getName());
        var splitter = new PercentageBasedSplitter(0.8, 10);
        var recommendationEngine = factory.build(recommender);

        var result = recommendationEngine.evaluate(aCasses.get(), splitter);
        double threshold = recommender.getThreshold();

        if (result.isEvaluationSkipped()) {
            var evaluationIsOptional = recommender.getThreshold() <= 0.0d;
            if (evaluationIsOptional) {
                return Optional.of(
                        activateRecommenderAboveThreshold(user, recommender, result, 0, threshold));
            }

            return Optional.of(skipRecommenderDueToFailedEvaluation(user, recommender, result));
        }

        double score = result.computeF1Score();
        if (score >= threshold) {
            return Optional.of(
                    activateRecommenderAboveThreshold(user, recommender, result, score, threshold));
        }

        return Optional
                .of(skipRecommenderBelowThreshold(user, recommender, result, score, threshold));
    }

    private EvaluatedRecommender skipRecommenderBelowThreshold(User user, Recommender recommender,
            EvaluationResult result, double score, double threshold)
    {
        String recommenderName = recommender.getName();
        log.info("[{}][{}]: Not activated ({} < threshold {})", user.getUsername(), recommenderName,
                score, threshold);
        info("Recommender [%s] not activated (%f < threshold %f)", recommenderName, score,
                threshold);
        return EvaluatedRecommender.makeInactive(recommender, result,
                format("Score {0,number,#.####} < threshold {1,number,#.####}", score, threshold));
    }

    private EvaluatedRecommender activateRecommenderAboveThreshold(User user,
            Recommender recommender, EvaluationResult result, double score, double threshold)
    {
        String recommenderName = recommender.getName();
        EvaluatedRecommender evaluatedRecommender = EvaluatedRecommender.makeActive(recommender,
                result,
                format("Score {0,number,#.####} >= threshold {1,number,#.####}", score, threshold));
        log.info("[{}][{}]: Activated ({} >= threshold {})", user.getUsername(), recommenderName,
                score, threshold);
        info("Recommender [%s] activated (%f >= threshold %f)", recommenderName, score, threshold);
        return evaluatedRecommender;
    }

    private EvaluatedRecommender skipRecommenderDueToFailedEvaluation(User user,
            Recommender recommender, EvaluationResult result)
    {
        String recommenderName = recommender.getName();
        String msg = String.format("Evaluation of recommender [%s] could not be performed: %s",
                recommenderName, result.getErrorMsg().orElse("unknown reason"));
        log.info("[{}][{}]: {}", user.getUsername(), recommenderName, msg);
        warn("%s", msg);
        return EvaluatedRecommender.makeInactiveWithoutEvaluation(recommender, msg);
    }

    private EvaluatedRecommender activateNonEvaluatableRecommender(String userName,
            Recommender recommender)
    {
        String recommenderName = recommender.getName();
        log.debug("[{}][{}]: Activating [{}] without evaluating - not evaluable", userName,
                recommenderName, recommenderName);
        info("Recommender [%s] activated without evaluating - not evaluable", recommenderName);
        return EvaluatedRecommender.makeActiveWithoutEvaluation(recommender);
    }

    private EvaluatedRecommender activateAlwaysOnRecommender(String userName,
            Recommender recommender)
    {
        String recommenderName = recommender.getName();
        log.debug("[{}][{}]: Activating [{}] without evaluating - always selected", userName,
                recommenderName, recommenderName);
        info("Recommender [%s] activated without evaluating - always selected", recommenderName);
        return EvaluatedRecommender.makeActiveWithoutEvaluation(recommender);
    }

    private EvaluatedRecommender skipRecommenderWithInvalidSettings(User user,
            Recommender recommender)
    {
        String recommenderName = recommender.getName();
        log.info("[{}][{}]: Recommender configured with invalid layer or feature "
                + "- skipping recommender", user.getUsername(), recommenderName);
        info("Recommender [%s] configured with invalid layer or feature - skipping recommender",
                recommenderName);
        return EvaluatedRecommender.makeInactiveWithoutEvaluation(recommender,
                "Invalid layer or feature");
    }

    private void sendMissingFactoryNotification(User user, Recommender recommender)
    {
        log.error("[{}][{}]: No recommender factory available for [{}]", user.getUsername(),
                recommender.getName(), recommender.getTool());
        appEventPublisher.publishEvent(
                RecommenderTaskNotificationEvent.builder(this, getProject(), user.getUsername()) //
                        .withMessage(LogMessage.error(this,
                                "No recommender factory available for %s", recommender.getTool())) //
                        .build());
    }

    private List<CAS> readCasses(Project aProject, String aUserName)
    {
        List<CAS> casses = new ArrayList<>();
        for (SourceDocument document : documentService.listSourceDocuments(aProject)) {
            try {
                // We should not have to modify the CASes... right? Fingers crossed.
                CAS cas = documentService.readAnnotationCas(document, aUserName, AUTO_CAS_UPGRADE,
                        SHARED_READ_ONLY_ACCESS);
                casses.add(cas);
            }
            catch (IOException e) {
                log.error("Cannot read annotation CAS.", e);
            }
        }
        return casses;
    }

    private Optional<Recommender> freshenRecommender(User aUser, Recommender r)
    {
        // Make sure we have the latest recommender config from the DB - the one from
        // the active recommenders list may be outdated
        Recommender recommender;
        try {
            recommender = recommendationService.getRecommender(r.getId());
        }
        catch (NoResultException e) {
            log.info("[{}][{}]: Recommender no longer available... skipping", aUser.getUsername(),
                    r.getName());
            return Optional.empty();
        }

        if (!recommender.isEnabled()) {
            log.debug("[{}][{}]: Disabled - skipping", aUser.getUsername(), recommender.getName());
            return Optional.empty();
        }

        return Optional.of(recommender);
    }
}
