package com.linkedin.venice.helix;

import com.linkedin.venice.meta.Store;
import com.sun.istack.internal.NotNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.I0Itec.zkclient.IZkChildListener;
import org.I0Itec.zkclient.IZkDataListener;
import org.apache.helix.AccessOption;
import org.apache.helix.manager.zk.ZkClient;
import org.apache.log4j.Logger;


/**
 * Metadata repository  Helix.
 */
public class HelixCachedMetadataRepository extends HelixMetadataRepository {

    private static final Logger logger = Logger.getLogger(HelixCachedMetadataRepository.class.getName());

    /**
     * Local map of all stores read from Zookeeper.
     */
    private Map<String, Store> storeMap;
    /**
     * Listener used when there is any store be created or deleted.
     */
    private final StoresChangedListener storesChangedListener = new StoresChangedListener();
    /**
     * Listener used when the data of one store is changed.
     */
    private final StoreDataChangedListener storeDataChangedListener = new StoreDataChangedListener();
    /**
     * Lock to control the concurrency requests to stores.
     */
    private final ReadWriteLock metadataLock = new ReentrantReadWriteLock();

    public HelixCachedMetadataRepository(@NotNull ZkClient zkClient, @NotNull String rootPath) {
        super(zkClient, rootPath);
    }

    public void init() {
        metadataLock.writeLock().lock();
        try {
            storeMap = new HashMap<>();

            List<Store> stores = dataAccessor.getChildren(rootPath, null, AccessOption.PERSISTENT);
            dataAccessor.subscribeChildChanges(rootPath, storesChangedListener);

            internalAddStores(stores);
        } finally {
            metadataLock.writeLock().unlock();
        }
    }

    private void internalAddStores(List<Store> stores) {
        for (Store s : stores) {
            if (storeMap.containsKey(s.getName())) {
                continue;
            }
            storeMap.put(s.getName(), s);
            dataAccessor.subscribeDataChanges(composeStorePath(s.getName()), storeDataChangedListener);
        }
    }

    @Override
    public Store getStore(@NotNull String name) {
        metadataLock.readLock().lock();
        try {
            if (storeMap.containsKey(name)) {
                return storeMap.get(name).cloneStore();
            }
            return null;
        } finally {
            metadataLock.readLock().unlock();
        }
    }

    @Override
    public void deleteStore(@NotNull String name) {
        metadataLock.writeLock().lock();
        try {
            dataAccessor.remove(composeStorePath(name), AccessOption.PERSISTENT);
            storeMap.remove(name);
        } finally {
            metadataLock.writeLock().unlock();
        }
    }

    @Override
    public void addStore(@NotNull Store store) {
        metadataLock.writeLock().lock();
        try {
            if (storeMap.containsKey(store.getName())) {
                throw new IllegalArgumentException("Store" + store.getName() + " is existed.");
            }
            dataAccessor.set(composeStorePath(store.getName()), store, AccessOption.PERSISTENT);
            dataAccessor.subscribeDataChanges(composeStorePath(store.getName()), storeDataChangedListener);
        } finally {
            metadataLock.writeLock().unlock();
        }
    }

    @Override
    public void updateStore(@NotNull Store store) {
        metadataLock.writeLock().lock();
        try {
            if (!storeMap.containsKey(store.getName())) {
                throw new IllegalArgumentException("Store" + store.getName() + " is not existed.");
            }
            Store originalStore = storeMap.get(store.getName());
            if (!originalStore.equals(store)) {
                dataAccessor.set(composeStorePath(store.getName()), store, AccessOption.PERSISTENT);
                storeMap.put(store.getName(), store);
            }
        } finally {
            metadataLock.writeLock().unlock();
        }
    }

    private class StoresChangedListener implements IZkChildListener {
        @Override
        public void handleChildChange(String parentPath, List<String> currentChilds)
            throws Exception {
            metadataLock.writeLock().lock();
            try {
                if (!parentPath.equals(rootPath)) {
                    logger.warn("Ignore the irrelevant children change of path:" + parentPath);
                    return;
                }

                List<String> addedChildren = new ArrayList<>();
                Set<String> deletedChildren = new HashSet<>(storeMap.keySet());

                for (String child : currentChilds) {
                    if (!storeMap.containsKey(child)) {
                        addedChildren.add(composeStorePath(child));
                    } else {
                        deletedChildren.remove(child);
                    }
                }

                if (addedChildren.size() > 0) {
                    List<Store> addedStores = dataAccessor.get(addedChildren, null, AccessOption.PERSISTENT);
                    internalAddStores(addedStores);
                }

                if (deletedChildren.size() > 0) {
                    //Delete from ZK at first then loccal cache.
                    dataAccessor.remove(new ArrayList<String>(deletedChildren), AccessOption.PERSISTENT);
                    for (String deletedChild : deletedChildren) {
                        storeMap.remove(deletedChild);
                    }
                }
            } finally {
                metadataLock.writeLock().unlock();
            }
        }
    }

    private class StoreDataChangedListener implements IZkDataListener {

        @Override
        public void handleDataChange(String dataPath, Object data)
            throws Exception {
            metadataLock.writeLock().lock();
            try {
                storeMap.put(extractStoreNameFromPath(dataPath), (Store) data);
            } finally {
                metadataLock.writeLock().unlock();
            }
        }

        @Override
        public void handleDataDeleted(String dataPath)
            throws Exception {
            metadataLock.writeLock().lock();
            try {
                storeMap.remove(extractStoreNameFromPath(dataPath));
            } finally {
                metadataLock.writeLock().unlock();
            }
        }
    }
}
