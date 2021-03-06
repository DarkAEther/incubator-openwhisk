/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package whisk.http

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling._
import akka.stream.{ActorMaterializer, OverflowStrategy, QueueOfferResult}
import akka.stream.scaladsl.{Flow, _}
import spray.json._

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

/**
 * Http client to talk to a known host.
 *
 * This class only handles the basic communication to the proper endpoints. It is up to its clients to interpret the
 * results. It is built on akka-http host-level connection pools; compared to single requests, it saves some time
 * on each request because it doesn't need to look up the pool corresponding to the host. It is also easier to add an
 * extra queueing mechanism.
 */
class PoolingRestClient(
  protocol: String,
  host: String,
  port: Int,
  queueSize: Int,
  httpFlow: Option[Flow[(HttpRequest, Promise[HttpResponse]), (Try[HttpResponse], Promise[HttpResponse]), Any]] = None)(
  implicit system: ActorSystem) {
  require(protocol == "http" || protocol == "https", "Protocol must be one of { http, https }.")

  protected implicit val context: ExecutionContext = system.dispatcher
  protected implicit val materializer: ActorMaterializer = ActorMaterializer()

  // Creates or retrieves a connection pool for the host.
  private val pool = if (protocol == "http") {
    Http().cachedHostConnectionPool[Promise[HttpResponse]](host = host, port = port)
  } else {
    Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](host = host, port = port)
  }

  // Additional queue in case all connections are busy. Should hardly ever be
  // filled in practice but can be useful, e.g., in tests starting many
  // asynchronous requests in a very short period of time.
  private val requestQueue = Source
    .queue(queueSize, OverflowStrategy.dropNew)
    .via(httpFlow.getOrElse(pool))
    .toMat(Sink.foreach({
      case ((Success(response), p)) => p.success(response)
      case ((Failure(error), p))    => p.failure(error)
    }))(Keep.left)
    .run

  /**
   * Execute an HttpRequest on the underlying connection pool.
   *
   * WARNING: It is **very** important that the resulting entity is either drained or discarded fully, so the connection
   * can be reused. Otherwise, the pool will dry up.
   *
   * @return a future holding the response from the server.
   */
  def request(futureRequest: Future[HttpRequest]): Future[HttpResponse] = futureRequest.flatMap { request =>
    val promise = Promise[HttpResponse]

    // When the future completes, we know whether the request made it
    // through the queue.
    requestQueue.offer(request -> promise).flatMap {
      case QueueOfferResult.Enqueued    => promise.future
      case QueueOfferResult.Dropped     => Future.failed(new Exception("DB request queue is full."))
      case QueueOfferResult.QueueClosed => Future.failed(new Exception("DB request queue was closed."))
      case QueueOfferResult.Failure(f)  => Future.failed(f)
    }
  }

  /**
   * Execute an HttpRequest on the underlying connection pool and return an unmarshalled result.
   *
   * @return either the unmarshalled result or a status code, if the status code is not a success (2xx class)
   */
  def requestJson[T: RootJsonReader](futureRequest: Future[HttpRequest]): Future[Either[StatusCode, T]] =
    request(futureRequest).flatMap { response =>
      if (response.status.isSuccess) {
        Unmarshal(response.entity.withoutSizeLimit).to[T].map(Right.apply)
      } else {
        // This is important, as it drains the entity stream.
        // Otherwise the connection stays open and the pool dries up.
        response.discardEntityBytes().future.map(_ => Left(response.status))
      }
    }

  def shutdown(): Future[Unit] = Future.successful(materializer.shutdown())
}

object PoolingRestClient {

  def mkRequest(method: HttpMethod,
                uri: Uri,
                body: Future[MessageEntity] = Future.successful(HttpEntity.Empty),
                headers: List[HttpHeader] = List.empty)(implicit ec: ExecutionContext): Future[HttpRequest] = {
    body.map { b =>
      HttpRequest(method, uri, headers, b)
    }
  }

  def mkJsonRequest(method: HttpMethod, uri: Uri, body: JsValue, headers: List[HttpHeader] = List.empty)(
    implicit ec: ExecutionContext): Future[HttpRequest] = {
    val b = Marshal(body).to[MessageEntity]
    mkRequest(method, uri, b, headers)
  }
}
