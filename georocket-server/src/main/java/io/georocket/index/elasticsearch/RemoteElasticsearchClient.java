package io.georocket.index.elasticsearch;

import io.georocket.util.HttpException;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.NoStackTraceThrowable;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.rxjava.core.Vertx;
import org.apache.commons.collections.CollectionUtils;
import org.jooq.lambda.tuple.Tuple2;
import rx.Completable;
import rx.Single;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * An Elasticsearch client using the HTTP API
 * @author Michel Kraemer
 */
public class RemoteElasticsearchClient implements ElasticsearchClient {
  private static Logger log = LoggerFactory.getLogger(RemoteElasticsearchClient.class);

  /**
   * The vertx instance
   */
  private final Vertx vertx;

  /**
   * The index to query against
   */
  private final String index;
  
  /**
   * The HTTP client used to talk to Elasticsearch
   */
  private final LoadBalancingHttpClient client;

  /**
   * The ID of the periodic timer that automatically updates the hosts
   * to connect to
   */
  private long autoUpdateHostsTimerId = -1;
  
  /**
   * Connect to an Elasticsearch instance
   * @param hosts the hosts to connect to
   * @param index the index to query against
   * @param autoUpdateHostsInterval interval of a periodic timer that
   * automatically updates the list of hosts to connect to (may be {@code null}
   * if the list of hosts should never be updated)
   * @param vertx a Vert.x instance
   */
  public RemoteElasticsearchClient(List<URI> hosts, String index,
      Duration autoUpdateHostsInterval, Vertx vertx) {
    this.vertx = vertx;
    this.index = index;
    client = new LoadBalancingHttpClient(vertx);
    client.setHosts(hosts);

    if (autoUpdateHostsInterval != null) {
      autoUpdateHostsTimerId = vertx.setPeriodic(
          autoUpdateHostsInterval.toMillis(), id -> updateHosts());
    }
  }

  @Override
  public void close() {
    client.close();
    if (autoUpdateHostsTimerId != -1) {
      vertx.cancelTimer(autoUpdateHostsTimerId);
    }
  }

  /**
   * Asynchronously update the list of Elasticsearch hosts
   */
  private void updateHosts() {
    client.performRequest("/_nodes/http").subscribe(response -> {
      JsonObject nodes = response.getJsonObject("nodes");

      List<URI> hosts = new ArrayList<>();
      for (String nodeId : nodes.fieldNames()) {
        JsonObject node = nodes.getJsonObject(nodeId);
        JsonObject http = node.getJsonObject("http");
        if (http == null) {
          // ignore this host. it does not have HTTP enabled
          continue;
        }

        String publishAddress = http.getString("publish_address");
        if (publishAddress == null) {
          // ignore this host. it does not have a publish address
          continue;
        }

        URI uri = URI.create("http://" + publishAddress);
        hosts.add(uri);
      }

      if (!CollectionUtils.isEqualCollection(hosts, client.getHosts())) {
        log.info("Updated list of Elasticsearch hosts: " + hosts);
      }

      client.setHosts(hosts);
    }, err ->
      log.error("Could not update list of Elasticsearch hosts", err)
    );
  }
  
  @Override
  public Single<JsonObject> bulkInsert(String type,
      List<Tuple2<String, JsonObject>> documents) {
    String uri = "/" + index + "/" + type + "/_bulk";
    
    // prepare the whole body now because it's much faster to send
    // it at once instead of using HTTP chunked mode.
    StringBuilder body = new StringBuilder();
    for (Tuple2<String, JsonObject> e : documents) {
      String id = e.v1;
      String source = e.v2.encode();
      JsonObject subject = new JsonObject().put("_id", id);
      body.append("{\"index\":")
          .append(subject.encode())
          .append("}\n")
          .append(source)
          .append("\n");
    }
    
    return client.performRequest(HttpMethod.POST, uri, body.toString());
  }
  
  @Override
  public Single<JsonObject> beginScroll(String type, JsonObject query,
      JsonObject postFilter, JsonObject aggregations, JsonObject parameters, String timeout) {
    String uri = "/" + index + "/" + type + "/_search";
    uri += "?scroll=" + timeout;
    
    JsonObject source = new JsonObject();
    parameters.forEach(entry ->
      source.put(entry.getKey(), entry.getValue()));

    if (query != null) {
      source.put("query", query);
    }
    if (postFilter != null) {
      source.put("post_filter", postFilter);
    }
    if (aggregations != null) {
      source.put("aggs", aggregations);
    }
    
    // sort by doc (fastest way to scroll)
    source.put("sort", new JsonArray().add("_doc"));
    
    return client.performRequest(HttpMethod.GET, uri, source.encode());
  }
  
  @Override
  public Single<JsonObject> continueScroll(String scrollId, String timeout) {
    String uri = "/_search/scroll";
    
    JsonObject source = new JsonObject();
    source.put("scroll", timeout);
    source.put("scroll_id", scrollId);
    
    return client.performRequest(HttpMethod.GET, uri, source.encode());
  }
  
  @Override
  public Single<JsonObject> search(String type, JsonObject query,
      JsonObject postFilter, JsonObject aggregations, JsonObject parameters) {
    String uri = "/" + index + "/" + type + "/_search";
    
    JsonObject source = new JsonObject();
    parameters.forEach(entry -> 
      source.put(entry.getKey(), entry.getValue()));
    
    if (query != null) {
      source.put("query", query);
    }
    if (postFilter != null) {
      source.put("post_filter", postFilter);
    }
    if (aggregations != null) {
      source.put("aggs", aggregations);
    }
    
    return client.performRequest(HttpMethod.GET, uri, source.encode());
  }

  @Override
  public Single<Long> count(String type, JsonObject query) {
    String uri = "/" + index + "/" + type + "/_count";

    JsonObject source = new JsonObject();
    if (query != null) {
      source.put("query", query);
    }

    return client.performRequest(HttpMethod.GET, uri, source.encode())
      .flatMap(sr -> {
        Long l = sr.getLong("count");
        if (l == null) {
          return Single.error(new NoStackTraceThrowable(
              "Could not count documents"));
        }
        return Single.just(l);
      });
  }

  @Override
  public Single<JsonObject> updateByQuery(String type, JsonObject postFilter,
      JsonObject script) {
    String uri = "/" + index + "/" + type + "/_update_by_query";

    JsonObject source = new JsonObject();
    if (postFilter != null) {
      source.put("post_filter", postFilter);
    }
    if (script != null) {
      source.put("script", script);
    }

    return client.performRequest(HttpMethod.POST, uri, source.encode());
  }
  
  @Override
  public Single<JsonObject> bulkDelete(String type, JsonArray ids) {
    String uri = "/" + index + "/" + type + "/_bulk";
    
    // prepare the whole body now because it's much faster to send
    // it at once instead of using HTTP chunked mode.
    StringBuilder body = new StringBuilder();
    for (int i = 0; i < ids.size(); ++i) {
      String id = ids.getString(i);
      JsonObject subject = new JsonObject().put("_id", id);
      body.append("{\"delete\":")
          .append(subject.encode())
          .append("}\n");
    }
    
    return client.performRequest(HttpMethod.POST, uri, body.toString());
  }
  
  @Override
  public Single<Boolean> indexExists() {
    return exists("/" + index);
  }
  
  @Override
  public Single<Boolean> typeExists(String type) {
    return exists("/" + index + "/_mapping/" + type);
  }
  
  /**
   * Check if the given URI exists by sending an empty request
   * @param uri uri to check
   * @return an observable emitting <code>true</code> if the request
   * was successful or <code>false</code> otherwise
   */
  private Single<Boolean> exists(String uri) {
    return client.performRequest(HttpMethod.HEAD, uri, null)
      .map(o -> true)
      .onErrorResumeNext(t -> {
        if (t instanceof HttpException && ((HttpException)t).getStatusCode() == 404) {
          return Single.just(false);
        }
        return Single.error(t);
      });
  }
  
  @Override
  public Single<Boolean> createIndex() {
    return createIndex(null);
  }

  @Override
  public Single<Boolean> createIndex(JsonObject settings) {
    String uri = "/" + index;

    String body = settings == null ? null :
      new JsonObject().put("settings", settings).encode();

    return client.performRequest(HttpMethod.PUT, uri, body)
        .map(res -> res.getBoolean("acknowledged", true));
  }

  /**
   * Ensure the Elasticsearch index exists
   * @return an observable that will emit a single item when the index has
   * been created or if it already exists
   */
  @Override
  public Completable ensureIndex() {
    // check if the index exists
    return indexExists().flatMapCompletable(exists -> {
      if (exists) {
        return Completable.complete();
      } else {
        // index does not exist. create it.
        return createIndex().flatMapCompletable(ack -> {
          if (ack) {
            return Completable.complete();
          }
          return Completable.error(new NoStackTraceThrowable("Index creation "
            + "was not acknowledged by Elasticsearch"));
        });
      }
    });
  }
  
  @Override
  public Single<Boolean> putMapping(String type, JsonObject mapping) {
    String uri = "/" + index + "/_mapping/" + type;
    return client.performRequest(HttpMethod.PUT, uri, mapping.encode())
      .map(res -> res.getBoolean("acknowledged", true));
  }
  
  /**
   * Ensure the Elasticsearch mapping exists
   * @param type the target type for the mapping
   * @return an observable that will emit a single item when the mapping has
   * been created or if it already exists
   */
  @Override
  public Completable ensureMapping(String type, JsonObject mapping) {
    // check if the target type exists
    return typeExists(type).flatMapCompletable(exists -> {
      if (exists) {
        return Completable.complete();
      } else {
        // target type does not exist. create the mapping.
        return putMapping(type, mapping).flatMapCompletable(ack -> {
          if (ack) {
            return Completable.complete();
          }
          return Completable.error(new NoStackTraceThrowable("Mapping creation "
            + "was not acknowledged by Elasticsearch"));
        });
      }
    });
  }

  @Override
  public Single<JsonObject> getMapping(String type) {
    return getMapping(type, null);
  }

  @Override
  public Single<JsonObject> getMapping(String type, String field) {
    String uri = "/" + index + "/_mapping/" + type;
    if (field != null) {
      uri += "/field/" + field;
    }
    return client.performRequest(HttpMethod.GET, uri, "");
  }

  @Override
  public Single<Boolean> isRunning() {
    return client.performRequestNoRetry(HttpMethod.HEAD, "/", null)
        .map(v -> true).onErrorReturn(t -> false);
  }
}
