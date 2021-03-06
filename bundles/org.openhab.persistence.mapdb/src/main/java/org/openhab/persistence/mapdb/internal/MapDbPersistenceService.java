/**
 * Copyright (c) 2010-2020 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.persistence.mapdb.internal;

import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.openhab.core.OpenHAB;
import org.openhab.core.common.ThreadPoolManager;
import org.openhab.core.items.Item;
import org.openhab.core.persistence.FilterCriteria;
import org.openhab.core.persistence.HistoricItem;
import org.openhab.core.persistence.PersistenceItemInfo;
import org.openhab.core.persistence.PersistenceService;
import org.openhab.core.persistence.QueryablePersistenceService;
import org.openhab.core.persistence.strategy.PersistenceStrategy;
import org.openhab.core.types.State;
import org.openhab.core.types.UnDefType;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * This is the implementation of the MapDB {@link PersistenceService}. To learn more about MapDB please visit their
 * <a href="http://www.mapdb.org/">website</a>.
 *
 * @author Jens Viebig - Initial contribution
 * @author Martin Kühl - Port to 3.x
 */
@NonNullByDefault
@Component(service = { PersistenceService.class, QueryablePersistenceService.class })
public class MapDbPersistenceService implements QueryablePersistenceService {

    private static final String SERVICE_ID = "mapdb";
    private static final String SERVICE_LABEL = "MapDB";
    private static final String DB_FOLDER_NAME = OpenHAB.getUserDataFolder() + File.separator + "persistence"
            + File.separator + "mapdb";
    private static final String DB_FILE_NAME = "storage.mapdb";

    private final Logger logger = LoggerFactory.getLogger(MapDbPersistenceService.class);

    private final ExecutorService threadPool = ThreadPoolManager.getPool(getClass().getSimpleName());

    /** holds the local instance of the MapDB database */

    private @NonNullByDefault({}) DB db;
    private @NonNullByDefault({}) Map<String, String> map;

    private transient Gson mapper = new GsonBuilder().registerTypeHierarchyAdapter(State.class, new StateTypeAdapter())
            .create();

    @Activate
    public void activate() {
        logger.debug("MapDB persistence service is being activated");

        File folder = new File(DB_FOLDER_NAME);
        if (!folder.exists()) {
            if (!folder.mkdirs()) {
                logger.warn("Failed to create one or more directories in the path '{}'", DB_FOLDER_NAME);
                logger.warn("MapDB persistence service activation has failed.");
                return;
            }
        }

        File dbFile = new File(DB_FOLDER_NAME, DB_FILE_NAME);
        db = DBMaker.newFileDB(dbFile).closeOnJvmShutdown().make();
        map = db.createTreeMap("itemStore").makeOrGet();
        logger.debug("MapDB persistence service is now activated");
    }

    @Deactivate
    public void deactivate() {
        logger.debug("MapDB persistence service deactivated");
        if (db != null) {
            db.close();
        }
        threadPool.shutdown();
    }

    @Override
    public String getId() {
        return SERVICE_ID;
    }

    @Override
    public String getLabel(@Nullable Locale locale) {
        return SERVICE_LABEL;
    }

    @Override
    public Set<PersistenceItemInfo> getItemInfo() {
        return map.values().stream().map(this::deserialize).flatMap(MapDbPersistenceService::streamOptional)
                .collect(Collectors.<PersistenceItemInfo> toUnmodifiableSet());
    }

    @Override
    public void store(Item item) {
        store(item, item.getName());
    }

    @Override
    public void store(Item item, @Nullable String alias) {
        if (item.getState() instanceof UnDefType) {
            return;
        }

        // PersistenceManager passes SimpleItemConfiguration.alias which can be null
        String localAlias = alias == null ? item.getName() : alias;
        logger.debug("store called for {}", localAlias);

        State state = item.getState();
        MapDbItem mItem = new MapDbItem();
        mItem.setName(localAlias);
        mItem.setState(state);
        mItem.setTimestamp(new Date());
        String json = serialize(mItem);
        map.put(localAlias, json);
        commit();
        logger.debug("Stored '{}' with state '{}' in MapDB database", localAlias, state.toString());
    }

    @Override
    public Iterable<HistoricItem> query(FilterCriteria filter) {
        String json = map.get(filter.getItemName());
        if (json == null) {
            return Collections.emptyList();
        }
        Optional<MapDbItem> item = deserialize(json);
        if (!item.isPresent()) {
            return Collections.emptyList();
        }
        return Collections.singletonList(item.get());
    }

    private String serialize(MapDbItem item) {
        return mapper.toJson(item);
    }

    @SuppressWarnings("null")
    private Optional<MapDbItem> deserialize(String json) {
        MapDbItem item = mapper.<MapDbItem> fromJson(json, MapDbItem.class);
        if (item == null || !item.isValid()) {
            logger.warn("Deserialized invalid item: {}", item);
            return Optional.empty();
        }
        return Optional.of(item);
    }

    private void commit() {
        threadPool.submit(() -> db.commit());
    }

    private static <T> Stream<T> streamOptional(Optional<T> opt) {
        if (!opt.isPresent()) {
            return Stream.empty();
        }
        return Stream.of(opt.get());
    }

    @Override
    public List<PersistenceStrategy> getDefaultStrategies() {
        return List.of(PersistenceStrategy.Globals.RESTORE, PersistenceStrategy.Globals.CHANGE);
    }
}
