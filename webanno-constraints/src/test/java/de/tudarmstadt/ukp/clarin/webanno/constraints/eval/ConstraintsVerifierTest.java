/*
 * Copyright 2015
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
package de.tudarmstadt.ukp.clarin.webanno.constraints.eval;

import static de.tudarmstadt.ukp.clarin.webanno.constraints.grammar.ConstraintsParser.parse;
import static org.apache.uima.fit.util.JCasUtil.select;
import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.uima.fit.factory.JCasFactory;
import org.apache.uima.jcas.JCas;
import org.junit.Ignore;
import org.junit.Test;

import de.tudarmstadt.ukp.clarin.webanno.constraints.evaluator.ConstraintsVerifier;
import de.tudarmstadt.ukp.clarin.webanno.constraints.evaluator.Verifiable;
import de.tudarmstadt.ukp.clarin.webanno.constraints.model.ParsedConstraints;
import de.tudarmstadt.ukp.dkpro.core.api.segmentation.type.Lemma;

@Ignore("Not fully implemented yet")
public class ConstraintsVerifierTest
{
    @Test
    public void test() throws Exception
    {
        ParsedConstraints constraints = parse(new File("src/test/resources/rules/6.rules"));

        // Get imports
        Map<String, String> imports = new LinkedHashMap<>();
        imports = constraints.getImports();

        // Get rules
        // List<Rule> rules = new ArrayList<>();

        JCas jcas = JCasFactory.createJCas();
        jcas.setDocumentText("Just some text.");

        Lemma lemma1 = new Lemma(jcas, 0, 1);
        lemma1.setValue("good");
        lemma1.addToIndexes();

        Lemma lemma2 = new Lemma(jcas, 1, 2);
        lemma2.setValue("bad");
        lemma2.addToIndexes();

        Verifiable cVerifier = new ConstraintsVerifier();

        for (Lemma lemma : select(jcas, Lemma.class)) {
            if (lemma == lemma1) {
                assertEquals(true, cVerifier.verify(lemma, constraints));
            }
            if (lemma == lemma2) {
                assertEquals(false, cVerifier.verify(lemma, constraints));
            }
        }
    }

    // private boolean verify(FeatureStructure aFS, Map<String, String>
    // aImports, List<Rule> aRules)
    // {
    // boolean isOk = false;
    // Type type = aFS.getType();
    // for (Feature feature : type.getFeatures()) {
    // if (feature.getRange().isPrimitive()) {
    // String value = aFS.getFeatureValueAsString(feature);
    // // Check if all the feature values are ok according to the rules;
    // }
    // else {
    // // Here some recursion would be in order
    // }
    // }
    // return isOk;
    // }
}
