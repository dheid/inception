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
package de.tudarmstadt.ukp.clarin.webanno.support.lambda;

import org.apache.wicket.Component;
import org.apache.wicket.behavior.Behavior;
import org.apache.wicket.event.IEvent;
import org.apache.wicket.markup.ComponentTag;
import org.danekja.java.util.function.serializable.SerializableBiConsumer;
import org.danekja.java.util.function.serializable.SerializableBooleanSupplier;
import org.danekja.java.util.function.serializable.SerializableConsumer;

public class LambdaBehavior
{
    public static Behavior onConfigure(SerializableConsumer<Component> aAction)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = 4955142510510102959L;

            @Override
            public void onConfigure(Component aComponent)
            {
                aAction.accept(aComponent);
            }
        };
    }

    public static Behavior onRemove(SerializableConsumer<Component> aAction)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = -8323963975999230350L;

            @Override
            public void onRemove(Component aComponent)
            {
                aAction.accept(aComponent);
            }
        };
    }

    public static <T> Behavior onEvent(Class<T> aEventClass,
            SerializableBiConsumer<Component, T> aAction)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = -1956074724077271777L;

            @Override
            public void onEvent(Component aComponent, IEvent<?> aEvent)
            {
                if (aEventClass.isAssignableFrom(aEvent.getPayload().getClass())) {
                    aAction.accept(aComponent, (T) aEvent.getPayload());
                }
            }
        };
    }

    public static Behavior onException(SerializableBiConsumer<Component, RuntimeException> aAction)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = 1927758103651261506L;

            @Override
            public void onException(Component aComponent, RuntimeException aException)
            {
                aAction.accept(aComponent, aException);
            }
        };
    }

    public static Behavior onComponentTag(SerializableBiConsumer<Component, ComponentTag> aAction)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = 1191220285070986757L;

            @Override
            public void onComponentTag(Component aComponent, ComponentTag aTag)
            {
                aAction.accept(aComponent, aTag);
            }
        };
    }

    public static Behavior visibleWhen(SerializableBooleanSupplier aPredicate)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = -7550330528381560032L;

            @Override
            public void onConfigure(Component aComponent)
            {
                aComponent.setVisible(aPredicate.getAsBoolean());
            }
        };
    }

    public static Behavior enabledWhen(SerializableBooleanSupplier aPredicate)
    {
        return new Behavior()
        {
            private static final long serialVersionUID = -1435780281900876366L;

            @Override
            public void onConfigure(Component aComponent)
            {
                aComponent.setEnabled(aPredicate.getAsBoolean());
            }
        };
    }
}
