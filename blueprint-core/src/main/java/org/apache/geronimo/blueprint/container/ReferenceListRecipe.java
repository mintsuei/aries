/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.geronimo.blueprint.container;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.AbstractCollection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.RandomAccess;
import java.util.concurrent.Callable;

import org.apache.geronimo.blueprint.ExtendedBlueprintContainer;
import org.apache.geronimo.blueprint.ExtendedReferenceListMetadata;
import org.apache.geronimo.blueprint.di.Recipe;
import org.apache.geronimo.blueprint.di.CollectionRecipe;
import org.apache.geronimo.blueprint.utils.DynamicCollection;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.osgi.service.blueprint.container.ReifiedType;
import org.osgi.service.blueprint.container.ComponentDefinitionException;
import org.osgi.service.blueprint.container.ServiceUnavailableException;
import org.osgi.service.blueprint.reflect.ReferenceListMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A recipe to create a managed collection of service references
 *
 * @author <a href="mailto:dev@geronimo.apache.org">Apache Geronimo Project</a>
 * @version $Rev: 760378 $, $Date: 2009-03-31 11:31:38 +0200 (Tue, 31 Mar 2009) $
 */
public class ReferenceListRecipe extends AbstractServiceReferenceRecipe {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReferenceListRecipe.class);

    private final ReferenceListMetadata metadata;
    private final List<ManagedCollection> collections = new ArrayList<ManagedCollection>();
    private final DynamicCollection<ServiceDispatcher> storage = new DynamicCollection<ServiceDispatcher>();
    private final List<ServiceDispatcher> unboundDispatchers = new ArrayList<ServiceDispatcher>();

    public ReferenceListRecipe(String name,
                         ExtendedBlueprintContainer blueprintContainer,
                         ReferenceListMetadata metadata,
                         CollectionRecipe listenersRecipe,
                         List<Recipe> explicitDependencies) {
        super(name, blueprintContainer, metadata, listenersRecipe, explicitDependencies);
        this.metadata = metadata;
    }

    @Override
    protected Object internalCreate() throws ComponentDefinitionException {
        try {
            if (explicitDependencies != null) {
                for (Recipe recipe : explicitDependencies) {
                    recipe.create();
                }
            }
            ProvidedObject object = new ProvidedObject();
            addObject(object, true);
            // Handle initial references
            createListeners();
            retrack();
            return object;
        } catch (ComponentDefinitionException t) {
            throw t;
        } catch (Throwable t) {
            throw new ComponentDefinitionException(t);
        }
    }

    protected void retrack() {
        List<ServiceReference> refs = getServiceReferences();
        if (refs != null) {
            for (ServiceReference ref : refs) {
                track(ref);
            }
        }
    }

    protected void track(ServiceReference reference) {
        if (storage != null) {
            try {
                // ServiceReferences may be tracked at multiple points:
                //  * first after the collection creation in #internalCreate()
                //  * in #postCreate() after listeners are created
                //  * after creation time if a new reference shows up
                //
                // In the first step, listeners are not created, so we add
                // the dispatcher to the unboundDispatchers list.  In the second
                // step, the dispatcher has already been added to the collection
                // so we just call the listener.
                //
                ServiceDispatcher dispatcher = findDispatcher(reference);
                if (dispatcher != null) {
                    if (!unboundDispatchers.remove(dispatcher)) {
                        return;
                    }
                } else {
                    dispatcher = new ServiceDispatcher(reference);
                    List<String> interfaces = new ArrayList<String>();
                    if (metadata.getInterface() != null) {
                        interfaces.add(metadata.getInterface());
                    }
                    if (metadata instanceof ExtendedReferenceListMetadata) {
                        boolean greedy = (((ExtendedReferenceListMetadata) metadata).getProxyMethod() & ExtendedReferenceListMetadata.PROXY_METHOD_GREEDY) != 0;
                        if (greedy) {
                            interfaces = Arrays.asList((String[]) reference.getProperty(Constants.OBJECTCLASS));
                        }
                    }
                    dispatcher.proxy = createProxy(dispatcher, interfaces);
                    if (!storage.add(dispatcher)) {
                        dispatcher.destroy();
                        return;
                    }
                }
                if (listeners != null) {
                    for (Listener listener : listeners) {
                        if (listener != null) {
                            listener.bind(dispatcher.reference, dispatcher.proxy);
                        }
                    }
                } else {
                    unboundDispatchers.add(dispatcher);
                }
            } catch (Throwable t) {
                LOGGER.info("Error tracking new service reference", t);
            }
        }
    }

    protected void untrack(ServiceReference reference) {
        if (storage != null) {
            ServiceDispatcher dispatcher = findDispatcher(reference);
            if (dispatcher != null) {
                if (listeners != null) {
                    for (Listener listener : listeners) {
                        if (listener != null) {
                            listener.unbind(dispatcher.reference, dispatcher.proxy);
                        }
                    }
                }
                storage.remove(dispatcher);
                dispatcher.destroy();
            }
        }
    }

    protected ServiceDispatcher findDispatcher(ServiceReference reference) {
        for (ServiceDispatcher dispatcher : storage) {
            if (dispatcher.reference == reference) {
                return dispatcher;
            }
        }
        return null;
    }

    protected ManagedCollection getManagedCollection(boolean useReferences) {
        for (ManagedCollection col : collections) {
            if (col.references == useReferences) {
                return col;
            }
        }
        ManagedCollection collection = new ManagedCollection(useReferences, storage);
        collections.add(collection);
        return collection;
    }

    /**
     * The ServiceDispatcher is used when creating the cglib proxy.
     * Thic class is responsible for getting the actual service that will be used.
     */
    public class ServiceDispatcher implements Callable<Object> {

        public ServiceReference reference;
        public Object service;
        public Object proxy;
        
        public ServiceDispatcher(ServiceReference reference) throws Exception {
            this.reference = reference;
        }

        public synchronized void destroy() {
            if (reference != null) {
                reference.getBundle().getBundleContext().ungetService(reference);
                reference = null;
                service = null;
                proxy = null;
            }
        }

        public synchronized Object call() throws Exception {
            if (reference == null) {
                throw new ServiceUnavailableException("Service is unavailable", getOsgiFilter());
            }
            if (service == null) {
                service = reference.getBundle().getBundleContext().getService(reference);
            }
            return service;
        }

    }

    public class ProvidedObject implements AggregateConverter.Convertible {

        public Object convert(ReifiedType type) {
            LOGGER.debug("Converting ManagedCollection to {}", type);
            if (!type.getRawClass().isAssignableFrom(List.class)) {
                throw new ComponentDefinitionException("<ref-list/> can only be converted to a List, not " + type);
            }
            boolean useRef = false;
            if (type instanceof ParameterizedType) {
                Type[] args = ((ParameterizedType) type).getActualTypeArguments();
                if (args != null && args.length == 1) {
                    useRef = (args[0] == ServiceReference.class);
                }
            }
            boolean references;
            if (metadata.getMemberType() == ReferenceListMetadata.USE_SERVICE_REFERENCE) {
                references = true;
            } else if (metadata.getMemberType() == ReferenceListMetadata.USE_SERVICE_OBJECT) {
                references = false;
            } else {
                references = useRef;
            }
            LOGGER.debug("ManagedCollection references={}", references);
            return getManagedCollection(references);
        }

    }

    /**
     * Base class for managed collections.
     *
     * TODO: list iterators should not be supported
     * TODO: rework the iteration so that if hasNext() has returned false, it will always return false
     * TODO: implement subList()
     */
    public static class ManagedCollection extends AbstractCollection implements List, RandomAccess {

        protected final DynamicCollection<ServiceDispatcher> dispatchers;
        protected boolean references;

        public ManagedCollection(boolean references, DynamicCollection<ServiceDispatcher> dispatchers) {
            this.references = references;
            this.dispatchers = dispatchers;
            LOGGER.debug("ManagedCollection references={}", references);
        }

        public boolean addDispatcher(ServiceDispatcher dispatcher) {
            return dispatchers.add(dispatcher);
        }

        public boolean removeDispatcher(ServiceDispatcher dispatcher) {
            return dispatchers.remove(dispatcher);
        }

        public DynamicCollection<ServiceDispatcher> getDispatchers() {
            return dispatchers;
        }

        public Iterator iterator() {
            return new ManagedListIterator(dispatchers.iterator());
        }

        public int size() {
            return dispatchers.size();
        }

        @Override
        public boolean add(Object o) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean remove(Object o) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean addAll(Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean retainAll(Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        @Override
        public boolean removeAll(Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public Object get(int index) {
            return references ? dispatchers.get(index).reference : dispatchers.get(index).proxy;
        }

        public int indexOf(Object o) {
            if (o == null) {
                throw new NullPointerException();
            }
            ListIterator e = listIterator();
            while (e.hasNext()) {
                if (o.equals(e.next())) {
                    return e.previousIndex();
                }
            }
            return -1;
        }

        public int lastIndexOf(Object o) {
            if (o == null) {
                throw new NullPointerException();
            }
            ListIterator e = listIterator(size());
            while (e.hasPrevious()) {
                if (o.equals(e.previous())) {
                    return e.nextIndex();
                }
            }
            return -1;
        }

        public ListIterator listIterator() {
            return listIterator(0);
        }

        public ListIterator listIterator(int index) {
            return new ManagedListIterator(dispatchers.iterator(index));
        }

        public List<ServiceDispatcher> subList(int fromIndex, int toIndex) {
            throw new UnsupportedOperationException("Not implemented");
        }

        public Object set(int index, Object element) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public void add(int index, Object element) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public Object remove(int index) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public boolean addAll(int index, Collection c) {
            throw new UnsupportedOperationException("This collection is read only");
        }

        public class ManagedListIterator implements ListIterator {

            protected final ListIterator<ServiceDispatcher> iterator;

            public ManagedListIterator(ListIterator<ServiceDispatcher> iterator) {
                this.iterator = iterator;
            }

            public boolean hasNext() {
                return iterator.hasNext();
            }

            public Object next() {
                return references ? iterator.next().reference : iterator.next().proxy;
            }

            public boolean hasPrevious() {
                return iterator.hasPrevious();
            }

            public Object previous() {
                return references ? iterator.previous().reference : iterator.previous().proxy;
            }

            public int nextIndex() {
                return iterator.nextIndex();
            }

            public int previousIndex() {
                return iterator.previousIndex();
            }

            public void remove() {
                throw new UnsupportedOperationException("This collection is read only");
            }

            public void set(Object o) {
                throw new UnsupportedOperationException("This collection is read only");
            }

            public void add(Object o) {
                throw new UnsupportedOperationException("This collection is read only");
            }
        }

    }


}
