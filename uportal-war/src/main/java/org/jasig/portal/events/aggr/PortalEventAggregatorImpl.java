/**
 * Licensed to Jasig under one or more contributor license
 * agreements. See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Jasig licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a
 * copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.jasig.portal.events.aggr;

import java.io.Serializable;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.Resource;
import javax.persistence.FlushModeType;

import org.apache.commons.lang.mutable.MutableInt;
import org.apache.commons.lang.mutable.MutableObject;
import org.hibernate.Cache;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.metadata.ClassMetadata;
import org.hibernate.type.CollectionType;
import org.hibernate.type.Type;
import org.jasig.portal.IPortalInfoProvider;
import org.jasig.portal.concurrency.FunctionWithoutResult;
import org.jasig.portal.concurrency.locking.IClusterLockService;
import org.jasig.portal.events.PortalEvent;
import org.jasig.portal.events.aggr.IEventAggregatorStatus.ProcessingType;
import org.jasig.portal.events.aggr.dao.IEventAggregationManagementDao;
import org.jasig.portal.events.aggr.session.EventSession;
import org.jasig.portal.events.aggr.session.EventSessionDao;
import org.jasig.portal.events.handlers.db.IPortalEventDao;
import org.jasig.portal.jpa.BaseAggrEventsJpaDao;
import org.jasig.portal.jpa.BaseRawEventsJpaDao.RawEventsTransactional;
import org.jasig.portal.spring.context.ApplicationEventFilter;
import org.jasig.portal.utils.cache.CacheKey;
import org.joda.time.DateTime;
import org.joda.time.Period;
import org.joda.time.ReadablePeriod;
import org.springframework.aop.framework.AopProxyUtils;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import com.google.common.collect.Sets;

@Service
public class PortalEventAggregatorImpl extends BaseAggrEventsJpaDao implements PortalEventAggregator, DisposableBean {
    private static final String EVENT_SESSION_CACHE_KEY_SOURCE = AggregateEventsHandler.class.getName() + "-EventSession";

    private IClusterLockService clusterLockService;
    private IPortalEventAggregationManager portalEventAggregationManager;
    private PortalEventDimensionPopulator portalEventDimensionPopulator;
    private IEventAggregationManagementDao eventAggregationManagementDao;
    private IPortalInfoProvider portalInfoProvider;
    private IPortalEventDao portalEventDao;
    private AggregationIntervalHelper intervalHelper;
    private EventSessionDao eventSessionDao;
    private Set<IPortalEventAggregator<PortalEvent>> portalEventAggregators = Collections.emptySet();
    private List<ApplicationEventFilter<PortalEvent>> applicationEventFilters = Collections.emptyList();
    
    private int eventAggregationBatchSize = 10000;
    private ReadablePeriod aggregationDelay = Period.seconds(30);
    
    private final Map<Class<?>, List<String>> entityCollectionRoles = new HashMap<Class<?>, List<String>>();
    private volatile boolean shutdown = false;
    
    @Autowired
    public void setPortalEventAggregationManager(IPortalEventAggregationManager portalEventAggregationManager) {
        this.portalEventAggregationManager = portalEventAggregationManager;
    }

    @Autowired
    public void setClusterLockService(IClusterLockService clusterLockService) {
        this.clusterLockService = clusterLockService;
    }

    @Autowired
    public void setPortalEventDimensionPopulator(PortalEventDimensionPopulator portalEventDimensionPopulator) {
        this.portalEventDimensionPopulator = portalEventDimensionPopulator;
    }

    @Autowired
    public void setEventAggregationManagementDao(IEventAggregationManagementDao eventAggregationManagementDao) {
        this.eventAggregationManagementDao = eventAggregationManagementDao;
    }

    @Autowired
    public void setPortalInfoProvider(IPortalInfoProvider portalInfoProvider) {
        this.portalInfoProvider = portalInfoProvider;
    }

    @Autowired
    public void setPortalEventDao(IPortalEventDao portalEventDao) {
        this.portalEventDao = portalEventDao;
    }

    @Autowired
    public void setIntervalHelper(AggregationIntervalHelper intervalHelper) {
        this.intervalHelper = intervalHelper;
    }

    @Autowired
    public void setEventSessionDao(EventSessionDao eventSessionDao) {
        this.eventSessionDao = eventSessionDao;
    }

    @Autowired
    public void setPortalEventAggregators(Set<IPortalEventAggregator<PortalEvent>> portalEventAggregators) {
        this.portalEventAggregators = portalEventAggregators;
    }

    @Resource(name="aggregatorEventFilters")
    public void setApplicationEventFilters(List<ApplicationEventFilter<PortalEvent>> applicationEventFilters) {
        this.applicationEventFilters = applicationEventFilters;
    }

    @Value("${org.jasig.portal.event.aggr.PortalEventAggregationManager.aggregationDelay:PT30S}")
    public void setAggregationDelay(ReadablePeriod aggregationDelay) {
        this.aggregationDelay = aggregationDelay;
    }
    
    @Value("${org.jasig.portal.event.aggr.PortalEventAggregationManager.eventAggregationBatchSize:10000}")
    public void setEventAggregationBatchSize(int eventAggregationBatchSize) {
        this.eventAggregationBatchSize = eventAggregationBatchSize;
    }

    @Override
    public void destroy() throws Exception {
        this.shutdown = true;
    }

    private void checkShutdown() {
        if (shutdown) {
            //Mark ourselves as interupted and throw an exception
            Thread.currentThread().interrupt();
            throw new RuntimeException("uPortal is shutting down, throwing an exeption to stop processing");
        }
    }
    
    @RawEventsTransactional
    @Override
    public EventProcessingResult doAggregateRawEvents() {
        //Do RawTX around AggrTX. The AggrTX is MUCH more likely to fail than the RawTX and this results in both rolling back
        return this.getTransactionOperations().execute(new TransactionCallback<EventProcessingResult>() {
            @Override
            public EventProcessingResult doInTransaction(TransactionStatus status) {
                return doAggregateRawEventsInternal();
            }
        });
    }
    
    @AggrEventsTransactional
    @Override
    public void evictAggregates(Map<Class<?>, Collection<Serializable>> entitiesToEvict) {
        int evictedEntities = 0;
        int evictedCollections = 0;
        
        final Session session = getEntityManager().unwrap(Session.class);
        final SessionFactory sessionFactory = session.getSessionFactory();
        final Cache cache = sessionFactory.getCache();
        
        
        for (final Entry<Class<?>, Collection<Serializable>> evictedEntityEntry : entitiesToEvict.entrySet()) {
            final Class<?> entityClass = evictedEntityEntry.getKey();
            final List<String> collectionRoles = getCollectionRoles(sessionFactory, entityClass);
            
            for (final Serializable id : evictedEntityEntry.getValue()) {
                cache.evictEntity(entityClass, id);
                evictedEntities++;
                
                for (final String collectionRole : collectionRoles) {
                    cache.evictCollection(collectionRole, id);
                    evictedCollections++;
                }
            }
        }
        
        logger.debug("Evicted {} entities and {} collections from hibernate caches", evictedEntities, evictedCollections);        
    }
    
    @Override
    public EventProcessingResult doCloseAggregations() {
        if (!this.clusterLockService.isLockOwner(AGGREGATION_LOCK_NAME)) {
            throw new IllegalStateException("The cluster lock " + AGGREGATION_LOCK_NAME + " must be owned by the current thread and server");
        }
        
        final IEventAggregatorStatus cleanUnclosedStatus = eventAggregationManagementDao.getEventAggregatorStatus(ProcessingType.CLEAN_UNCLOSED, true);
        
        //Update status with current server name
        final String serverName = this.portalInfoProvider.getUniqueServerName();
        cleanUnclosedStatus.setServerName(serverName);
        cleanUnclosedStatus.setLastStart(new DateTime());
        
        //Determine date of most recently aggregated data
        final IEventAggregatorStatus eventAggregatorStatus = eventAggregationManagementDao.getEventAggregatorStatus(ProcessingType.AGGREGATION, false);
        if (eventAggregatorStatus == null || eventAggregatorStatus.getLastEventDate() == null) {
            //Nothing has been aggregated, skip unclosed cleanup
            
            cleanUnclosedStatus.setLastEnd(new DateTime());
            eventAggregationManagementDao.updateEventAggregatorStatus(cleanUnclosedStatus);
            
            return new EventProcessingResult(0, null, null, true);
        }
        
        //Calculate clean end date from most recent aggregation minus the purge delay
        final DateTime lastAggregatedDate = eventAggregatorStatus.getLastEventDate();
        final DateTime lastCleanUnclosedDate = cleanUnclosedStatus.getLastEventDate();
        
        if (lastAggregatedDate.equals(lastCleanUnclosedDate)) {
            logger.debug("No events aggregated since last unclosed aggregation cleaning, skipping clean: {}", lastAggregatedDate);
            return new EventProcessingResult(0, null, null, true);
        }
        
        int closedAggregations = 0;
        final Thread currentThread = Thread.currentThread();
        final String currentName = currentThread.getName();
        try {
            currentThread.setName(currentName + "-" + lastAggregatedDate);
            
            //For each interval the aggregator supports, cleanup the unclosed aggregations
            for (final AggregationInterval interval : AggregationInterval.values()) {
                //Determine the current interval
                final AggregationIntervalInfo currentInterval = intervalHelper.getIntervalInfo(interval, lastAggregatedDate);
                if (currentInterval != null) {
                    //Use the start of the current interval as the end of the current cleanup range
                    final DateTime cleanBeforeDate = currentInterval.getStart();
                    
                    //Determine the end of the cleanup to use as the start of the cleanup range
                    final DateTime cleanAfterDate;
                    if (lastCleanUnclosedDate == null) {
                        cleanAfterDate = new DateTime(0);
                    }
                    else {
                        final AggregationIntervalInfo previousInterval = intervalHelper.getIntervalInfo(interval, lastCleanUnclosedDate);
                        if (previousInterval == null) {
                            cleanAfterDate = new DateTime(0);
                        }
                        else {
                            cleanAfterDate = previousInterval.getStart();
                        }
                    }
                    
                    if (cleanAfterDate.equals(cleanBeforeDate)) {
                        logger.debug("No cleaning needed for {} starting at {}",  new Object[] { interval, cleanAfterDate, cleanBeforeDate});
                        continue;
                    }
                    
                    logger.debug("Cleaning unclosed {} aggregations between {} and {}",  new Object[] { interval, cleanAfterDate, cleanBeforeDate});

                    for (final IPortalEventAggregator<PortalEvent> portalEventAggregator : portalEventAggregators) {
                        checkShutdown();
                        
                        final Class<? extends IPortalEventAggregator<?>> aggregatorType = getClass(portalEventAggregator);
                        
                        //Get aggregator specific interval info config
                        AggregatedIntervalConfig aggregatorIntervalConfig = eventAggregationManagementDao.getAggregatedIntervalConfig(aggregatorType);
                        if (aggregatorIntervalConfig == null) {
                            aggregatorIntervalConfig = eventAggregationManagementDao.getDefaultAggregatedIntervalConfig();
                        }
                        
                        //If the aggregator is being used for the specified interval call cleanUnclosedAggregations
                        if (aggregatorIntervalConfig.isIncluded(interval)) {
                            closedAggregations += portalEventAggregator.cleanUnclosedAggregations(cleanAfterDate, cleanBeforeDate, interval);
                        }
                    }
                }
            }
        }
        finally {
            currentThread.setName(currentName);
        }
        
        //Update the status object and store it
        cleanUnclosedStatus.setLastEventDate(lastAggregatedDate); 
        cleanUnclosedStatus.setLastEnd(new DateTime());
        eventAggregationManagementDao.updateEventAggregatorStatus(cleanUnclosedStatus);
        
        return new EventProcessingResult(closedAggregations, lastCleanUnclosedDate, lastAggregatedDate, true);
    }
    
    @SuppressWarnings("unchecked")
    protected final <T> Class<T> getClass(T object) {
        return (Class<T>)AopProxyUtils.ultimateTargetClass(object);
    }
    
    private List<String> getCollectionRoles(final SessionFactory sessionFactory, final Class<?> entityClass) {
        List<String> collectionRoles = entityCollectionRoles.get(entityClass);
        if (collectionRoles != null) {
            return collectionRoles;
        }
        
        final com.google.common.collect.ImmutableList.Builder<String> collectionRolesBuilder = ImmutableList.builder();
        final ClassMetadata classMetadata = sessionFactory.getClassMetadata(entityClass);
        for (final Type type : classMetadata.getPropertyTypes()) {
            if (type.isCollectionType()) {
                collectionRolesBuilder.add(((CollectionType)type).getRole());
            }                
        }
        
        collectionRoles = collectionRolesBuilder.build();
        entityCollectionRoles.put(entityClass, collectionRoles);
        
        return collectionRoles;
    }

    private EventProcessingResult doAggregateRawEventsInternal() {
        if (!this.clusterLockService.isLockOwner(AGGREGATION_LOCK_NAME)) {
            throw new IllegalStateException("The cluster lock " + AGGREGATION_LOCK_NAME + " must be owned by the current thread and server");
        }
        
        if (!this.portalEventDimensionPopulator.isCheckedDimensions()) {
            //First time aggregation has happened, run populateDimensions to ensure enough dimension data exists
            final boolean populatedDimensions = this.portalEventAggregationManager.populateDimensions();
            if (!populatedDimensions) {
                this.logger.warn("Aborting raw event aggregation, populateDimensions returned false so the state of date/time dimensions is unknown");
                return null;
            }
        }
        
        //Flush any dimension creation before aggregation
        this.getEntityManager().flush();
        this.getEntityManager().setFlushMode(FlushModeType.COMMIT);

        final IEventAggregatorStatus eventAggregatorStatus = eventAggregationManagementDao.getEventAggregatorStatus(ProcessingType.AGGREGATION, true);
        
        //Update status with current server name
        final String serverName = this.portalInfoProvider.getUniqueServerName();
        final String previousServerName = eventAggregatorStatus.getServerName();
        if (previousServerName != null && !serverName.equals(previousServerName)) {
            this.logger.debug("Last aggregation run on {} clearing all aggregation caches", previousServerName);
            final Session session = getEntityManager().unwrap(Session.class);
            final Cache cache = session.getSessionFactory().getCache();
            cache.evictEntityRegions();
        }
        
        eventAggregatorStatus.setServerName(serverName);
        
        //Calculate date range for aggregation
        DateTime lastAggregated = eventAggregatorStatus.getLastEventDate();
        if (lastAggregated == null) {
            lastAggregated = new DateTime(0);
        }
        
        final DateTime newestEventTime = DateTime.now().minus(this.aggregationDelay).secondOfMinute().roundFloorCopy();
        
        final Thread currentThread = Thread.currentThread();
        final String currentName = currentThread.getName();
        final MutableInt events = new MutableInt();
        final MutableObject lastEventDate = new MutableObject(newestEventTime);
        
        try {
            currentThread.setName(currentName + "-" + lastAggregated + "_" + newestEventTime);
        
            logger.debug("Starting aggregation of events between {} (inc) and {} (exc)", lastAggregated, newestEventTime);
            
            //Do aggregation, capturing the start and end dates
            eventAggregatorStatus.setLastStart(DateTime.now());
            portalEventDao.aggregatePortalEvents(lastAggregated, newestEventTime, this.eventAggregationBatchSize, new AggregateEventsHandler(events, lastEventDate, eventAggregatorStatus));
            eventAggregatorStatus.setLastEventDate((DateTime)lastEventDate.getValue());
            eventAggregatorStatus.setLastEnd(new DateTime());
        }
        finally {
            currentThread.setName(currentName);
        }
        
        //Store the results of the aggregation
        eventAggregationManagementDao.updateEventAggregatorStatus(eventAggregatorStatus);
        
        final boolean complete = this.eventAggregationBatchSize <= 0 || events.intValue() < this.eventAggregationBatchSize;
        return new EventProcessingResult(events.intValue(), lastAggregated, eventAggregatorStatus.getLastEventDate(), complete);
    }

    private final class AggregateEventsHandler extends FunctionWithoutResult<PortalEvent> {
        //Event Aggregation Context - used by aggregators to track state
        private final EventAggregationContext eventAggregationContext = new EventAggregationContextImpl(); 
        private final MutableInt eventCounter;
        private final MutableObject lastEventDate;
        private final IEventAggregatorStatus eventAggregatorStatus;

        //pre-compute the set of intervals that our event aggregators support and only bother tracking those
        private final Set<AggregationInterval> handledIntervals;
        
        //Local tracking of the current aggregation interval and info about said interval
        private final Map<AggregationInterval, AggregationIntervalInfo> currentIntervalInfo = new EnumMap<AggregationInterval, AggregationIntervalInfo>(AggregationInterval.class);
        
        //Local caches of per-aggregator config data, shouldn't ever change for the duration of an aggregation run
        private final Map<Class<? extends IPortalEventAggregator<?>>, AggregatedGroupConfig> aggregatorGroupConfigs = new HashMap<Class<? extends IPortalEventAggregator<?>>, AggregatedGroupConfig>();
        private final Map<Class<? extends IPortalEventAggregator<?>>, AggregatedIntervalConfig> aggregatorIntervalConfigs = new HashMap<Class<? extends IPortalEventAggregator<?>>, AggregatedIntervalConfig>();
        private final Map<Class<? extends IPortalEventAggregator<?>>, Map<AggregationInterval, AggregationIntervalInfo>> aggregatorReadOnlyIntervalInfo = new HashMap<Class<? extends IPortalEventAggregator<?>>, Map<AggregationInterval,AggregationIntervalInfo>>();
        private final AggregatedGroupConfig defaultAggregatedGroupConfig;
        private final AggregatedIntervalConfig defaultAggregatedIntervalConfig;
        
        private AggregateEventsHandler(MutableInt eventCounter, MutableObject lastEventDate, IEventAggregatorStatus eventAggregatorStatus) {
            this.eventCounter = eventCounter;
            this.lastEventDate = lastEventDate;
            this.eventAggregatorStatus = eventAggregatorStatus;
            this.defaultAggregatedGroupConfig = eventAggregationManagementDao.getDefaultAggregatedGroupConfig();
            this.defaultAggregatedIntervalConfig = eventAggregationManagementDao.getDefaultAggregatedIntervalConfig();
            
            //Create the set of intervals that are actually being aggregated
            final Set<AggregationInterval> handledIntervalsNotIncluded = EnumSet.allOf(AggregationInterval.class);
            final Set<AggregationInterval> handledIntervalsBuilder = EnumSet.noneOf(AggregationInterval.class);
            for (final IPortalEventAggregator<PortalEvent> portalEventAggregator : portalEventAggregators) {
                final Class<? extends IPortalEventAggregator<?>> aggregatorType = getClass(portalEventAggregator);
                
                //Get aggregator specific interval info config
                final AggregatedIntervalConfig aggregatorIntervalConfig = this.getAggregatorIntervalConfig(aggregatorType);
                
                for (final Iterator<AggregationInterval> intervalsIterator = handledIntervalsNotIncluded.iterator(); intervalsIterator.hasNext(); ) {
                    final AggregationInterval interval = intervalsIterator.next();
                    if (aggregatorIntervalConfig.isIncluded(interval)) {
                        handledIntervalsBuilder.add(interval);
                        intervalsIterator.remove();
                    }
                }
            }
            this.handledIntervals = Sets.immutableEnumSet(handledIntervalsBuilder);
        }

        @Override
        protected void applyWithoutResult(PortalEvent event) {
            if (shutdown) {
                //Mark ourselves as interupted and throw an exception
                Thread.currentThread().interrupt();
                throw new RuntimeException("uPortal is shutting down, throwing an exeption to stop aggregation");
            }
            
            final DateTime eventDate = event.getTimestampAsDate();
            this.lastEventDate.setValue(eventDate);
            
            //If no interval data yet populate it.
            if (this.currentIntervalInfo.isEmpty()) {
                initializeIntervalInfo(eventDate);
            }
            
            //Check each interval to see if an interval boundary has been crossed
            for (final AggregationInterval interval : handledIntervals) {
                AggregationIntervalInfo intervalInfo = this.currentIntervalInfo.get(interval);
                if (intervalInfo != null && !intervalInfo.getEnd().isAfter(eventDate)) { //if there is no IntervalInfo that interval must not be supported in the current environment 
                    logger.debug("Crossing {} Interval, triggered by {}", interval, event);
                    this.doHandleIntervalBoundary(interval, this.currentIntervalInfo);
                    
                    intervalInfo = intervalHelper.getIntervalInfo(interval, eventDate); 
                    this.currentIntervalInfo.put(interval, intervalInfo);
                    
                    this.aggregatorReadOnlyIntervalInfo.clear(); //Clear out cached per-aggregator interval info whenever a current interval info changes
                }
            }
            
            //Aggregate the event
            this.doAggregateEvent(event);
            
            //Update the status object with the event date
            this.lastEventDate.setValue(eventDate);
        }

        private void initializeIntervalInfo(final DateTime eventDate) {
            final DateTime intervalDate;
            final DateTime lastEventDate = this.eventAggregatorStatus.getLastEventDate();
            if (lastEventDate != null) {
                //If there was a previously aggregated event use that date to make sure an interval is not missed
                intervalDate = lastEventDate;
            }
            else {
                //Otherwise just use the current event date
                intervalDate = eventDate;
            }
            
            for (final AggregationInterval interval : this.handledIntervals) {
                final AggregationIntervalInfo intervalInfo = intervalHelper.getIntervalInfo(interval, intervalDate);
                if (intervalInfo != null) {
                    this.currentIntervalInfo.put(interval, intervalInfo);
                }
                else {
                    this.currentIntervalInfo.remove(interval);
                }
            }
        }
        
        private void doAggregateEvent(PortalEvent item) {
            checkShutdown();
            
            eventCounter.increment();

            for (final ApplicationEventFilter<PortalEvent> applicationEventFilter : applicationEventFilters) {
                if (!applicationEventFilter.supports(item)) {
                    logger.trace("Skipping event {} - {} excluded by filter {}", new Object[] { eventCounter, item,
                            applicationEventFilter });
                    return;
                }
            }
            logger.trace("Aggregating event {} - {}", eventCounter, item);
            
            //Load or create the event session
            EventSession eventSession = getEventSession(item);
            
            //Give each aggregator a chance at the event
            for (final IPortalEventAggregator<PortalEvent> portalEventAggregator : portalEventAggregators) {
                if (portalEventAggregator.supports(item.getClass())) {
                    final Class<? extends IPortalEventAggregator<?>> aggregatorType = getClass(portalEventAggregator);
                    
                    //Get aggregator specific interval info map
                    final Map<AggregationInterval, AggregationIntervalInfo> aggregatorIntervalInfo = this.getAggregatorIntervalInfo(aggregatorType);
                    
                    //If there is an event session get the aggregator specific version of it
                    if (eventSession != null) {
                        final AggregatedGroupConfig aggregatorGroupConfig = getAggregatorGroupConfig(aggregatorType);
                        
                        final CacheKey key = CacheKey.build(EVENT_SESSION_CACHE_KEY_SOURCE, eventSession, aggregatorGroupConfig);
                        EventSession filteredEventSession = this.eventAggregationContext.getAttribute(key);
                        if (filteredEventSession == null) {
                            filteredEventSession = new FilteredEventSession(eventSession, aggregatorGroupConfig);
                            this.eventAggregationContext.setAttribute(key, filteredEventSession);
                        }
                        eventSession = filteredEventSession;
                    }
                    
                    //Aggregation magic happens here!
                    portalEventAggregator.aggregateEvent(item, eventSession, eventAggregationContext, aggregatorIntervalInfo);
                }
            }
        }

        protected EventSession getEventSession(PortalEvent item) {
            final String eventSessionId = item.getEventSessionId();
            
            //First check the aggregation context for a cached session event, fall back
            //to asking the DAO if nothing in the context, cache the result
            final CacheKey key = CacheKey.build(EVENT_SESSION_CACHE_KEY_SOURCE, eventSessionId);
            EventSession eventSession = this.eventAggregationContext.getAttribute(key);
            if (eventSession == null) {
                eventSession = eventSessionDao.getEventSession(item);
                this.eventAggregationContext.setAttribute(key, eventSession);
            }
            
            //Record the session access
            eventSession.recordAccess(item.getTimestampAsDate());
            eventSessionDao.storeEventSession(eventSession);
            
            return eventSession;
        }
        
        private void doHandleIntervalBoundary(AggregationInterval interval, Map<AggregationInterval, AggregationIntervalInfo> intervals) {
            for (final IPortalEventAggregator<PortalEvent> portalEventAggregator : portalEventAggregators) {
                
                final Class<? extends IPortalEventAggregator<?>> aggregatorType = getClass(portalEventAggregator);
                final AggregatedIntervalConfig aggregatorIntervalConfig = this.getAggregatorIntervalConfig(aggregatorType);
                
                //If the aggreagator is configured to use the interval notify it of the interval boundary
                if (aggregatorIntervalConfig.isIncluded(interval)) {
                    final Map<AggregationInterval, AggregationIntervalInfo> aggregatorIntervalInfo = this.getAggregatorIntervalInfo(aggregatorType);
                    portalEventAggregator.handleIntervalBoundary(interval, eventAggregationContext, aggregatorIntervalInfo);
                }
            }
        }
        
        /**
         * @return The interval info map for the aggregator
         */
        protected Map<AggregationInterval, AggregationIntervalInfo> getAggregatorIntervalInfo(final Class<? extends IPortalEventAggregator<?>> aggregatorType) {
            final AggregatedIntervalConfig aggregatorIntervalConfig = this.getAggregatorIntervalConfig(aggregatorType);
            
            Map<AggregationInterval, AggregationIntervalInfo> intervalInfo = this.aggregatorReadOnlyIntervalInfo.get(aggregatorType);
            if (intervalInfo == null) {
                final Builder<AggregationInterval, AggregationIntervalInfo> intervalInfoBuilder = ImmutableMap.builder();
                
                for (Map.Entry<AggregationInterval, AggregationIntervalInfo> intervalInfoEntry : this.currentIntervalInfo.entrySet()) {
                    final AggregationInterval key = intervalInfoEntry.getKey();
                    if (aggregatorIntervalConfig.isIncluded(key)) {
                        intervalInfoBuilder.put(key, intervalInfoEntry.getValue());
                    }
                }
                
                intervalInfo = intervalInfoBuilder.build();
                aggregatorReadOnlyIntervalInfo.put(aggregatorType, intervalInfo);
            }
            
            return intervalInfo;
        }

        /**
         * @return The group config for the aggregator, returns the default config if no aggregator specific config is set
         */
        protected AggregatedGroupConfig getAggregatorGroupConfig(final Class<? extends IPortalEventAggregator<?>> aggregatorType) {
            AggregatedGroupConfig config = this.aggregatorGroupConfigs.get(aggregatorType);
            if (config == null) {
                config = eventAggregationManagementDao.getAggregatedGroupConfig(aggregatorType);
                if (config == null) {
                    config = this.defaultAggregatedGroupConfig;
                }
                this.aggregatorGroupConfigs.put(aggregatorType, config);
            }
            return config;
        }
        
        /**
         * @return The interval config for the aggregator, returns the default config if no aggregator specific config is set
         */
        protected AggregatedIntervalConfig getAggregatorIntervalConfig(final Class<? extends IPortalEventAggregator<?>> aggregatorType) {
            AggregatedIntervalConfig config = this.aggregatorIntervalConfigs.get(aggregatorType);
            if (config == null) {
                config = eventAggregationManagementDao.getAggregatedIntervalConfig(aggregatorType);
                if (config == null) {
                    config = this.defaultAggregatedIntervalConfig;
                }
                this.aggregatorIntervalConfigs.put(aggregatorType, config);
            }
            return config;
        }
        
        @SuppressWarnings("unchecked")
        protected <T> Class<T> getClass(T object) {
            return (Class<T>)AopProxyUtils.ultimateTargetClass(object);
        }
    }
}