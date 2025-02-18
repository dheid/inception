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
package de.tudarmstadt.ukp.inception.ui.core.dashboard.projectlist;

import org.apache.wicket.markup.html.basic.Label;
import org.apache.wicket.model.IModel;

import de.tudarmstadt.ukp.clarin.webanno.model.Project;
import de.tudarmstadt.ukp.clarin.webanno.support.dialog.ConfirmationDialogContentPanel_ImplBase;

public class LeaveProjectConfirmationDialogPanel
    extends ConfirmationDialogContentPanel_ImplBase
{
    private static final long serialVersionUID = 7050666954726883326L;

    public LeaveProjectConfirmationDialogPanel(String aId, IModel<Project> aModel)
    {
        super(aId);
        queue(new Label("name", aModel.map(Project::getName)));
    }
}
