/*
 * Copyright 2020
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
package de.tudarmstadt.ukp.inception.metrics;

public interface InceptionMetrics
{
    /**
     * Retrieve the total number of currently active (i.e. logged in) users
     */
    public long getActiveUsersTotal();
    /**
     * Retrieve the total number of enabled users
     */
    public long getEnbabledUsersTotal();
    /**
     * Retrieve the total number of documents
     */
    public long getDocumentsTotal();
    /**
     * Retrieve the total number of annotation documents
     */
    public long getAnnotationDocumentsTotal();
    
    /**
     * Retrieve the total number of currently enabled recommenders
     */
    public long getEnabledRecommendersTotal();
}
