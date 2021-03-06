/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.index.shard;

import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.collect.Tuple;
import org.elasticsearch.common.logging.ESLogger;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.env.NodeEnvironment;
import org.elasticsearch.index.settings.IndexSettings;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class ShardPath {
    public static final String INDEX_FOLDER_NAME = "index";
    public static final String TRANSLOG_FOLDER_NAME = "translog";

    private final Path path;
    private final String indexUUID;
    private final ShardId shardId;
    private final Path shardStatePath;


    public ShardPath(Path path, Path shardStatePath, String indexUUID, ShardId shardId) {
        this.path = path;
        this.indexUUID = indexUUID;
        this.shardId = shardId;
        this.shardStatePath = shardStatePath;
    }

    public Path resolveTranslog() {
        return path.resolve(TRANSLOG_FOLDER_NAME);
    }

    public Path resolveIndex() {
        return path.resolve(INDEX_FOLDER_NAME);
    }

    public Path getDataPath() {
        return path;
    }

    public boolean exists() {
        return Files.exists(path);
    }

    public String getIndexUUID() {
        return indexUUID;
    }

    public ShardId getShardId() {
        return shardId;
    }

    public Path getShardStatePath() {
        return shardStatePath;
    }

    /**
     * This method walks through the nodes shard paths to find the data and state path for the given shard. If multiple
     * directories with a valid shard state exist the one with the highest version will be used.
     * <b>Note:</b> this method resolves custom data locations for the shard.
     */
    public static ShardPath loadShardPath(ESLogger logger, NodeEnvironment env, ShardId shardId, @IndexSettings Settings indexSettings) throws IOException {
        final String indexUUID = indexSettings.get(IndexMetaData.SETTING_UUID, IndexMetaData.INDEX_UUID_NA_VALUE);
        final Path[] paths = env.availableShardPaths(shardId);
        Path loadedPath = null;
        for (Path path : paths) {
            ShardStateMetaData load = ShardStateMetaData.FORMAT.loadLatestState(logger, path);
            if (load != null) {
                if ((load.indexUUID.equals(indexUUID) || IndexMetaData.INDEX_UUID_NA_VALUE.equals(load.indexUUID)) == false) {
                    logger.warn("{} found shard on path: [{}] with a different index UUID - this shard seems to be leftover from a different index with the same name. Remove the leftover shard in order to reuse the path with the current index", shardId, path);
                    throw new IllegalStateException(shardId + " index UUID in shard state was: " + load.indexUUID + " expected: " + indexUUID + " on shard path: " + path);
                }
                if (loadedPath == null) {
                    loadedPath = path;
                } else{
                    throw new IllegalStateException(shardId + " more than one shard state found");
                }
            }

        }
        if (loadedPath == null) {
            return null;
        } else {
            final Path dataPath;
            final Path statePath = loadedPath;
            if (NodeEnvironment.hasCustomDataPath(indexSettings)) {
                dataPath = env.resolveCustomLocation(indexSettings, shardId);
            } else {
                dataPath = statePath;
            }
            logger.debug("{} loaded data path [{}], state path [{}]", shardId, dataPath, statePath);
            return new ShardPath(dataPath, statePath, indexUUID, shardId);
        }
    }

    /** Maps each path.data path to a "guess" of how many bytes the shards allocated to that path might additionally use over their
     *  lifetime; we do this so a bunch of newly allocated shards won't just all go the path with the most free space at this moment. */
    private static Map<Path,Long> getEstimatedReservedBytes(NodeEnvironment env, long avgShardSizeInBytes, Iterable<IndexShard> shards) throws IOException {
        long totFreeSpace = 0;
        for (NodeEnvironment.NodePath nodePath : env.nodePaths()) {
            totFreeSpace += nodePath.fileStore.getUsableSpace();
        }

        // Very rough heurisic of how much disk space we expect the shard will use over its lifetime, the max of current average
        // shard size across the cluster and 5% of the total available free space on this node:
        long estShardSizeInBytes = Math.max(avgShardSizeInBytes, (long) (totFreeSpace/20.0));

        // Collate predicted (guessed!) disk usage on each path.data:
        Map<Path,Long> reservedBytes = new HashMap<>();
        for (IndexShard shard : shards) {
            Path dataPath = NodeEnvironment.shardStatePathToDataPath(shard.shardPath().getShardStatePath());

            // Remove indices/<index>/<shardID> subdirs from the statePath to get back to the path.data/<lockID>:
            Long curBytes = reservedBytes.get(dataPath);
            if (curBytes == null) {
                curBytes = 0L;
            }
            reservedBytes.put(dataPath, curBytes + estShardSizeInBytes);
        }       

        return reservedBytes;
    }

    public static ShardPath selectNewPathForShard(NodeEnvironment env, ShardId shardId, @IndexSettings Settings indexSettings,
                                                  long avgShardSizeInBytes, Iterable<IndexShard> shards) throws IOException {

        final Path dataPath;
        final Path statePath;
        
        final String indexUUID = indexSettings.get(IndexMetaData.SETTING_UUID, IndexMetaData.INDEX_UUID_NA_VALUE);

        if (NodeEnvironment.hasCustomDataPath(indexSettings)) {
            dataPath = env.resolveCustomLocation(indexSettings, shardId);
            statePath = env.nodePaths()[0].resolve(shardId);
        } else {

            Map<Path,Long> estReservedBytes = getEstimatedReservedBytes(env, avgShardSizeInBytes, shards);

            // TODO - do we need something more extensible? Yet, this does the job for now...
            final NodeEnvironment.NodePath[] paths = env.nodePaths();
            NodeEnvironment.NodePath bestPath = null;
            long maxUsableBytes = Long.MIN_VALUE;
            for (NodeEnvironment.NodePath nodePath : paths) {
                FileStore fileStore = nodePath.fileStore;
                long usableBytes = fileStore.getUsableSpace();
                Long reservedBytes = estReservedBytes.get(nodePath.path);
                if (reservedBytes != null) {
                    // Deduct estimated reserved bytes from usable space:
                    usableBytes -= reservedBytes;
                }
                if (usableBytes > maxUsableBytes) {
                    maxUsableBytes = usableBytes;
                    bestPath = nodePath;
                }
            }

            statePath = bestPath.resolve(shardId);
            dataPath = statePath;
        }

        return new ShardPath(dataPath, statePath, indexUUID, shardId);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        final ShardPath shardPath = (ShardPath) o;
        if (shardId != null ? !shardId.equals(shardPath.shardId) : shardPath.shardId != null) {
            return false;
        }
        if (indexUUID != null ? !indexUUID.equals(shardPath.indexUUID) : shardPath.indexUUID != null) {
            return false;
        }
        if (path != null ? !path.equals(shardPath.path) : shardPath.path != null) {
            return false;
        }

        return true;
    }

    @Override
    public int hashCode() {
        int result = path != null ? path.hashCode() : 0;
        result = 31 * result + (indexUUID != null ? indexUUID.hashCode() : 0);
        result = 31 * result + (shardId != null ? shardId.hashCode() : 0);
        return result;
    }

    @Override
    public String toString() {
        return "ShardPath{" +
                "path=" + path +
                ", indexUUID='" + indexUUID + '\'' +
                ", shard=" + shardId +
                '}';
    }
}
