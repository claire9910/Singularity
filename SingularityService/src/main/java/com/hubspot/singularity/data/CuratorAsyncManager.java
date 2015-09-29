package com.hubspot.singularity.data;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.api.BackgroundCallback;
import org.apache.curator.framework.api.CuratorEvent;
import org.apache.curator.utils.ZKPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Optional;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import com.hubspot.singularity.SingularityId;
import com.hubspot.singularity.config.SingularityConfiguration;
import com.hubspot.singularity.data.transcoders.IdTranscoder;
import com.hubspot.singularity.data.transcoders.Transcoder;
import com.hubspot.singularity.data.transcoders.Transcoders;

public abstract class CuratorAsyncManager extends CuratorManager {

  private static final Logger LOG = LoggerFactory.getLogger(CuratorAsyncManager.class);

  public CuratorAsyncManager(CuratorFramework curator, SingularityConfiguration configuration) {
    super(curator, configuration);
  }

  private <T> List<T> getAsyncChildrenThrows(final String parent, final Transcoder<T> transcoder) throws Exception {
    final List<String> children = getChildren(parent);
    final List<String> paths = Lists.newArrayListWithCapacity(children.size());

    for (String child : children) {
      paths.add(ZKPaths.makePath(parent, child));
    }

    return getAsyncThrows(parent, paths, transcoder);
  }

  private <T> List<T> getAsyncThrows(final String pathNameForLogs, final Collection<String> paths, final Transcoder<T> transcoder) throws Exception {
    final List<T> objects = Lists.newArrayListWithCapacity(paths.size());

    if (paths.isEmpty()) {
      return objects;
    }

    final CountDownLatch latch = new CountDownLatch(paths.size());
    final AtomicInteger missing = new AtomicInteger();
    final AtomicInteger bytes = new AtomicInteger();

    final BackgroundCallback callback = new BackgroundCallback() {

      @Override
      public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
        if (event.getData() == null || event.getData().length == 0) {
          LOG.trace("Expected active node {} but it wasn't there", event.getPath());

          missing.incrementAndGet();
          latch.countDown();

          return;
        }

        bytes.getAndAdd(event.getData().length);

        objects.add(transcoder.fromBytes(event.getData()));

        latch.countDown();
      }
    };

    final long start = System.currentTimeMillis();

    for (String path : paths) {
      curator.getData().inBackground(callback).forPath(path);
    }

    checkLatch(latch, pathNameForLogs);

    log("Fetched", Optional.<Integer> of(objects.size()), Optional.<Integer> of(bytes.get()), start, pathNameForLogs);

    return objects;
  }

  private void checkLatch(CountDownLatch latch, String path) throws InterruptedException {
    if (!latch.await(configuration.getZookeeperAsyncTimeout(), TimeUnit.MILLISECONDS)) {
      throw new IllegalStateException(String.format("Timed out waiting response for objects from %s, waited %s millis", path, configuration.getZookeeperAsyncTimeout()));
    }
  }

  private <T extends SingularityId> List<T> getChildrenAsIdsForParentsThrows(final String pathNameforLogs, final Collection<String> parents, final IdTranscoder<T> idTranscoder) throws Exception {
    if (parents.isEmpty()) {
      return Collections.emptyList();
    }

    final List<T> objects = Lists.newArrayListWithExpectedSize(parents.size());

    final CountDownLatch latch = new CountDownLatch(parents.size());
    final AtomicInteger missing = new AtomicInteger();

    final BackgroundCallback callback = new BackgroundCallback() {

      @Override
      public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
        if (event.getChildren() == null || event.getChildren().size() == 0) {
          LOG.trace("Expected children for node {} - but found none", event.getPath());

          missing.incrementAndGet();
          latch.countDown();

          return;
        }

        objects.addAll(Lists.transform(event.getChildren(), Transcoders.getFromStringFunction(idTranscoder)));

        latch.countDown();
      }
    };

    final long start = System.currentTimeMillis();

    for (String parent : parents) {
      curator.getChildren().inBackground(callback).forPath(parent);
    }

    checkLatch(latch, pathNameforLogs);

    log("Fetched", Optional.<Integer> of(objects.size()), Optional.<Integer> absent(), start, pathNameforLogs);

    return objects;
  }

  protected <T extends SingularityId> List<T> getChildrenAsIdsForParents(final String pathNameforLogs, final Collection<String> parents, final IdTranscoder<T> idTranscoder) {
    try {
      return getChildrenAsIdsForParentsThrows(pathNameforLogs, parents, idTranscoder);
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }
  }

  protected <T extends SingularityId> List<T> getChildrenAsIds(final String rootPath, final IdTranscoder<T> idTranscoder) {
    return Lists.transform(getChildren(rootPath), Transcoders.getFromStringFunction(idTranscoder));
  }

  private <T extends SingularityId> List<T> existsThrows(final String pathNameforLogs, final Collection<String> paths, final IdTranscoder<T> idTranscoder) throws Exception {
    if (paths.isEmpty()) {
      return Collections.emptyList();
    }

    final List<T> objects = Lists.newArrayListWithCapacity(paths.size());

    final CountDownLatch latch = new CountDownLatch(paths.size());

    final BackgroundCallback callback = new BackgroundCallback() {

      @Override
      public void processResult(CuratorFramework client, CuratorEvent event) throws Exception {
        if (event.getStat() == null) {
          latch.countDown();

          return;
        }

        objects.add(Transcoders.getFromStringFunction(idTranscoder).apply(ZKPaths.getNodeFromPath(event.getPath())));

        latch.countDown();
      }
    };

    final long start = System.currentTimeMillis();

    for (String path : paths) {
      curator.checkExists().inBackground(callback).forPath(path);
    }

    checkLatch(latch, pathNameforLogs);

    log("Fetched", Optional.<Integer> of(objects.size()), Optional.<Integer> absent(), start, pathNameforLogs);

    return objects;
  }

  protected <T extends SingularityId> List<T> exists(final String pathNameForLogs, final Collection<String> paths, final IdTranscoder<T> idTranscoder) {
    try {
      return existsThrows(pathNameForLogs, paths, idTranscoder);
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }
  }

  protected <T> List<T> getAsync(final String pathNameForLogs, final Collection<String> paths, final Transcoder<T> transcoder) {
    try {
      return getAsyncThrows(pathNameForLogs, paths, transcoder);
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }
  }

  protected <T> List<T> getAsyncChildren(final String parent, final Transcoder<T> transcoder) {
    try {
      return getAsyncChildrenThrows(parent, transcoder);
    } catch (Throwable t) {
      throw Throwables.propagate(t);
    }
  }

}

