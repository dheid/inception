/*
 * Copyright 2018
 * Ubiquitous Knowledge Processing (UKP) Lab and FG Language Technology
 * Technische Universität Darmstadt
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *  http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.clarin.webanno.api.annotation.adapter;

import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.CHAIN_TYPE;
import static de.tudarmstadt.ukp.clarin.webanno.api.WebAnnoConst.PROJECT_TYPE_ANNOTATION;
import static de.tudarmstadt.ukp.clarin.webanno.model.AnchoringMode.TOKENS;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.ANY_OVERLAP;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.NO_OVERLAP;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.OVERLAP_ONLY;
import static de.tudarmstadt.ukp.clarin.webanno.model.OverlapMode.STACKING_ONLY;
import static java.util.Arrays.asList;
import static org.apache.commons.lang3.StringUtils.substring;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.util.List;

import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.fit.testing.factory.TokenBuilder;
import org.apache.uima.jcas.JCas;
import org.junit.Before;
import org.junit.Test;

import de.tudarmstadt.ukp.clarin.webanno.api.annotation.exception.AnnotationException;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.exception.MultipleSentenceCoveredException;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupportRegistry;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.feature.FeatureSupportRegistryImpl;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.layer.LayerSupportRegistry;
import de.tudarmstadt.ukp.clarin.webanno.api.annotation.layer.LayerSupportRegistryImpl;
import de.tudarmstadt.ukp.clarin.webanno.model.AnnotationLayer;
import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.model.SourceDocument;
import de.tudarmstadt.ukp.dkpro.core.api.coref.type.CoreferenceChain;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Sentence;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Token;

public class ChainAdapterTest
{
    private LayerSupportRegistry layerSupportRegistry;
    private FeatureSupportRegistry featureSupportRegistry;
    private Project project;
    private AnnotationLayer corefLayer;
    private JCas jcas;
    private SourceDocument document;
    private String username;
    private List<SpanLayerBehavior> behaviors;

    @Before
    public void setup() throws Exception
    {
        if (jcas == null) {
            jcas = JCasFactory.createJCas();
        }
        else {
            jcas.reset();
        }

        username = "user";

        project = new Project();
        project.setId(1l);
        project.setMode(PROJECT_TYPE_ANNOTATION);

        document = new SourceDocument();
        document.setId(1l);
        document.setProject(project);

        corefLayer = new AnnotationLayer(
                substring(CoreferenceChain.class.getName(), 0,
                        CoreferenceChain.class.getName().length() - ChainAdapter.CHAIN.length()),
                "Coreference", CHAIN_TYPE, project, true, TOKENS, ANY_OVERLAP);
        corefLayer.setId(1l);

        layerSupportRegistry = new LayerSupportRegistryImpl(asList());
        featureSupportRegistry = new FeatureSupportRegistryImpl(asList());

        behaviors = asList(new SpanOverlapBehavior(), new SpanCrossSentenceBehavior(),
                new SpanAnchoringModeBehavior());
    }

    @Test
    public void thatSpanCrossSentenceBehaviorOnCreateThrowsException()
    {
        corefLayer.setCrossSentence(false);

        TokenBuilder<Token, Sentence> builder = new TokenBuilder<>(Token.class, Sentence.class);
        builder.buildTokens(jcas, "This is a test .\nThis is sentence two .");

        ChainAdapter sut = new ChainAdapter(layerSupportRegistry, featureSupportRegistry, null,
                corefLayer, () -> asList(), behaviors);

        assertThatExceptionOfType(MultipleSentenceCoveredException.class)
                .isThrownBy(() -> sut.addSpan(document, username, jcas.getCas(), 0,
                        jcas.getDocumentText().length()))
                .withMessageContaining("covers multiple sentences");
    }

    @Test
    public void thatSpanOverlapBehaviorOnCreateWorks() throws AnnotationException
    {
        TokenBuilder<Token, Sentence> builder = new TokenBuilder<>(Token.class, Sentence.class);
        builder.buildTokens(jcas, "This is a test .");

        ChainAdapter sut = new ChainAdapter(layerSupportRegistry, featureSupportRegistry, null,
                corefLayer, () -> asList(), behaviors);

        // First time should work
        corefLayer.setOverlapMode(ANY_OVERLAP);
        sut.addSpan(document, username, jcas.getCas(), 0, 1);

        // Adding another annotation at the same place DOES NOT work
        corefLayer.setOverlapMode(NO_OVERLAP);
        assertThatExceptionOfType(AnnotationException.class)
                .isThrownBy(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .withMessageContaining("no overlap or stacking");

        corefLayer.setOverlapMode(OVERLAP_ONLY);
        assertThatExceptionOfType(AnnotationException.class)
                .isThrownBy(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .withMessageContaining("stacking is not allowed");

        // Adding another annotation at the same place DOES work
        corefLayer.setOverlapMode(STACKING_ONLY);
        assertThatCode(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .doesNotThrowAnyException();

        corefLayer.setOverlapMode(ANY_OVERLAP);
        assertThatCode(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .doesNotThrowAnyException();
    }

    @Test
    public void thatSpanAnchoringAndStackingBehaviorsWorkInConcert() throws AnnotationException
    {
        TokenBuilder<Token, Sentence> builder = new TokenBuilder<>(Token.class, Sentence.class);
        builder.buildTokens(jcas, "This is a test .");

        ChainAdapter sut = new ChainAdapter(layerSupportRegistry, featureSupportRegistry, null,
                corefLayer, () -> asList(), behaviors);

        // First time should work - we annotate the whole word "This"
        corefLayer.setOverlapMode(ANY_OVERLAP);
        sut.addSpan(document, username, jcas.getCas(), 0, 4);

        // Adding another annotation at the same place DOES NOT work
        // Here we annotate "T" but it should be expanded to "This"
        corefLayer.setOverlapMode(NO_OVERLAP);
        assertThatExceptionOfType(AnnotationException.class)
                .isThrownBy(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .withMessageContaining("no overlap or stacking");

        corefLayer.setOverlapMode(OVERLAP_ONLY);
        assertThatExceptionOfType(AnnotationException.class)
                .isThrownBy(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .withMessageContaining("stacking is not allowed");

        // Adding another annotation at the same place DOES work
        // Here we annotate "T" but it should be expanded to "This"
        corefLayer.setOverlapMode(STACKING_ONLY);
        assertThatCode(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .doesNotThrowAnyException();

        corefLayer.setOverlapMode(ANY_OVERLAP);
        assertThatCode(() -> sut.addSpan(document, username, jcas.getCas(), 0, 1))
                .doesNotThrowAnyException();
    }
}
