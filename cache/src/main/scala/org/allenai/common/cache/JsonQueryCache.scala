package org.allenai.common.cache

import org.allenai.common.Config._

import com.typesafe.config.Config
import redis.clients.jedis.{ Jedis, JedisPool, JedisPoolConfig, Protocol }
import spray.json._

object JsonQueryCache {
  /** Factory method for creating a cache instance from config.
    * The config must have keys for `hostname` and `clientPrefix`. It may also optionally have
    * keys for `port` and `timeoutMillis`; if not given, these values are set to the Jedis defaults.
    */
  def fromConfig[V](config: Config)(implicit jsonFormat: JsonFormat[V]): JsonQueryCache[V] = {
    // Required fields.
    val hostname: String = config[String]("hostname")
    val clientPrefix: String = config[String]("clientPrefix")

    // Optional overrides for Jedis defaults.
    val port: Int = config.get[Int]("port") getOrElse Protocol.DEFAULT_PORT
    val timeoutMillis: Int = config.get[Int]("timeoutMillis") getOrElse Protocol.DEFAULT_TIMEOUT

    new JsonQueryCache[V](
      clientPrefix,
      new JedisPool(new JedisPoolConfig, hostname, port, timeoutMillis)
    )
  }
}

/** Class holding a Redis cache of query results. This is meant to store any value `T` where
  * `T : spray.json.JsonFormat` (any `T` with a json serialization as per spray json), keyed on
  * string query. Multiple cache instances (instances pointing to different Redis caches) need to be
  * configured to have different JedisPools.
  * @param clientPrefix an identifier for the client using this caching mechanism, which will become
  * part of the cache key (prepended to the actual query)
  * @param pool the JedisPool that the client should use to serve requests
  */
class JsonQueryCache[V: JsonFormat] private[cache] (clientPrefix: String, pool: JedisPool) {

  /** @return the cache key for the query, with client prefix prepended */
  protected def keyForQuery(query: String): String = s"${clientPrefix}_$query"

  /** Retrieves the value for a passed key.
    * @param query key for stored value (not including client prefix)
    * @return Option containing value, None if not found or timed out (async)
    */
  def get(query: String): Option[V] = {
    withResource[Option[V]] { client: Jedis =>
      Option(client.get(keyForQuery(query))) map { response: String =>
        response.parseJson.convertTo[V]
      }
    }
  }

  /** Puts a key->value pair in the cache.
    * @param query key for value (not including client prefix)
    * @param response Value you want stored in cache
    */
  def put(query: String, response: V): Unit = withResource[Unit] { client: Jedis =>
    client.set(keyForQuery(query), response.toJson.compactPrint)
  }

  /** Deletes a key->value pair from the cache.
    * @param query key for value you want to delete (not including client prefix)
    */
  def del(query: String): Unit = withResource[Unit] { client: Jedis =>
    client.del(keyForQuery(query))
  }

  /** Runs the given operation, handling fetching and closing the Jedis connection. */
  private def withResource[T](operation: (Jedis => T)): T = {
    var resource: Jedis = null
    try {
      resource = pool.getResource
      operation(resource)
    } finally {
      if (resource != null) {
        resource.close()
      }
    }
  }
}

