package sttp.client4.prometheus

import java.util.concurrent.ConcurrentHashMap
import sttp.client4.{wrappers, _}
import io.prometheus.client.{CollectorRegistry, Counter, Gauge, Histogram, Summary}
import sttp.client4.listener.{ListenerBackend, RequestListener}
import sttp.client4.prometheus.PrometheusBackend.RequestCollectors
import sttp.client4.wrappers.FollowRedirectsBackend
import sttp.model.StatusCode

import scala.collection.mutable

object PrometheusBackend {
  val DefaultHistogramName = "sttp_request_latency"
  val DefaultRequestsInProgressGaugeName = "sttp_requests_in_progress"
  val DefaultSuccessCounterName = "sttp_requests_success_count"
  val DefaultErrorCounterName = "sttp_requests_error_count"
  val DefaultFailureCounterName = "sttp_requests_failure_count"
  val DefaultRequestSizeName = "sttp_request_size_bytes"
  val DefaultResponseSizeName = "sttp_response_size_bytes"

  val DefaultMethodLabel = "method"
  val DefaultStatusLabel = "status"

  def apply(delegate: SyncBackend): SyncBackend =
    apply(delegate, PrometheusConfig.Default)

  def apply[F[_]](delegate: Backend[F]): Backend[F] =
    apply(delegate, PrometheusConfig.Default)

  def apply[F[_]](delegate: WebSocketBackend[F]): WebSocketBackend[F] =
    apply(delegate, PrometheusConfig.Default)

  def apply[F[_], S](delegate: StreamBackend[F, S]): StreamBackend[F, S] =
    apply(delegate, PrometheusConfig.Default)

  def apply[F[_], S](delegate: WebSocketStreamBackend[F, S]): WebSocketStreamBackend[F, S] =
    apply(delegate, PrometheusConfig.Default)

  def apply(delegate: SyncBackend, config: PrometheusConfig): SyncBackend =
    // redirects should be handled before prometheus
    FollowRedirectsBackend(ListenerBackend(delegate, RequestListener.lift(listener(config), delegate.monad)))

  def apply[F[_]](delegate: Backend[F], config: PrometheusConfig): Backend[F] =
    wrappers.FollowRedirectsBackend[F](
      ListenerBackend(delegate, RequestListener.lift(listener(config), delegate.monad))
    )

  def apply[F[_]](delegate: WebSocketBackend[F], config: PrometheusConfig): WebSocketBackend[F] =
    wrappers.FollowRedirectsBackend(ListenerBackend(delegate, RequestListener.lift(listener(config), delegate.monad)))

  def apply[F[_], S](delegate: StreamBackend[F, S], config: PrometheusConfig): StreamBackend[F, S] =
    wrappers.FollowRedirectsBackend(ListenerBackend(delegate, RequestListener.lift(listener(config), delegate.monad)))

  def apply[F[_], S](delegate: WebSocketStreamBackend[F, S], config: PrometheusConfig): WebSocketStreamBackend[F, S] =
    wrappers.FollowRedirectsBackend(ListenerBackend(delegate, RequestListener.lift(listener(config), delegate.monad)))

  private def listener(config: PrometheusConfig): PrometheusListener =
    new PrometheusListener(
      (req: GenericRequest[_, _]) => config.requestToHistogramNameMapper(req),
      (req: GenericRequest[_, _]) => config.requestToInProgressGaugeNameMapper(req),
      (rr: (GenericRequest[_, _], Response[_])) => config.responseToSuccessCounterMapper(rr._1, rr._2),
      (rr: (GenericRequest[_, _], Response[_])) => config.responseToErrorCounterMapper(rr._1, rr._2),
      (r: (GenericRequest[_, _], Throwable)) => config.requestToFailureCounterMapper(r._1, r._2),
      (req: GenericRequest[_, _]) => config.requestToSizeSummaryMapper(req),
      (rr: (GenericRequest[_, _], Response[_])) => config.responseToSizeSummaryMapper(rr._1, rr._2),
      config.collectorRegistry,
      cacheFor(histograms, config.collectorRegistry),
      cacheFor(gauges, config.collectorRegistry),
      cacheFor(counters, config.collectorRegistry),
      cacheFor(summaries, config.collectorRegistry)
    )

  /** Add, if not present, a "method" label. That is, if the user already supplied such a label, it is left as-is.
    *
    * @param config
    *   The collector config to which the label should be added.
    * @return
    *   The modified collector config. The config can be used when configuring the backend using [[apply]].
    */
  def addMethodLabel[T <: BaseCollectorConfig](config: T, req: GenericRequest[_, _]): config.T = {
    val methodLabel: Option[(String, String)] =
      if (config.labels.map(_._1.toLowerCase).contains(DefaultMethodLabel)) {
        None
      } else {
        Some((DefaultMethodLabel, req.method.method.toUpperCase))
      }

    config.addLabels(methodLabel.toList)
  }

  /** Add, if not present, a "status" label. That is, if the user already supplied such a label, it is left as-is.
    *
    * @param config
    *   The collector config to which the label should be added.
    * @return
    *   The modified collector config. The config can be used when configuring the backend using [[apply]].
    */
  def addStatusLabel[T <: BaseCollectorConfig](config: T, resp: Response[_]): config.T = {
    val statusLabel: Option[(String, String)] =
      if (config.labels.map(_._1.toLowerCase).contains(DefaultStatusLabel)) {
        None
      } else {
        Some((DefaultStatusLabel, mapStatusToLabelValue(resp.code)))
      }

    config.addLabels(statusLabel.toList)
  }

  private def mapStatusToLabelValue(s: StatusCode): String =
    if (s.isInformational) "1xx"
    else if (s.isSuccess) "2xx"
    else if (s.isRedirect) "3xx"
    else if (s.isClientError) "4xx"
    else if (s.isServerError) "5xx"
    else s.code.toString

  /** Clear cached collectors (gauges and histograms) both from the given collector registry, and from the backend.
    */
  def clear(collectorRegistry: CollectorRegistry): Unit = {
    collectorRegistry.clear()
    histograms.remove(collectorRegistry)
    gauges.remove(collectorRegistry)
    counters.remove(collectorRegistry)
    summaries.remove(collectorRegistry)
    ()
  }

  /*
  Each collector can be registered in a collector registry only once - however there might be multiple backends registered
  with the same collector (trying to register a collector under the same name twice results in an exception).
  Hence, we need to store a global cache o created histograms/gauges, so that we can properly re-use them.
   */

  private val histograms = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Histogram]]
  private val gauges = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Gauge]]
  private val counters = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Counter]]
  private val summaries = new mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, Summary]]

  private def cacheFor[T](
      cache: mutable.WeakHashMap[CollectorRegistry, ConcurrentHashMap[String, T]],
      collectorRegistry: CollectorRegistry
  ): ConcurrentHashMap[String, T] =
    cache.synchronized {
      cache.getOrElseUpdate(collectorRegistry, new ConcurrentHashMap[String, T]())
    }
  final case class RequestCollectors(maybeTimer: Option[Histogram.Timer], maybeGauge: Option[Gauge.Child])
}

class PrometheusListener(
    requestToHistogramNameMapper: GenericRequest[_, _] => Option[HistogramCollectorConfig],
    requestToInProgressGaugeNameMapper: GenericRequest[_, _] => Option[CollectorConfig],
    requestToSuccessCounterMapper: ((GenericRequest[_, _], Response[_])) => Option[CollectorConfig],
    requestToErrorCounterMapper: ((GenericRequest[_, _], Response[_])) => Option[CollectorConfig],
    requestToFailureCounterMapper: ((GenericRequest[_, _], Exception)) => Option[CollectorConfig],
    requestToSizeSummaryMapper: GenericRequest[_, _] => Option[CollectorConfig],
    responseToSizeSummaryMapper: ((GenericRequest[_, _], Response[_])) => Option[CollectorConfig],
    collectorRegistry: CollectorRegistry,
    histogramsCache: ConcurrentHashMap[String, Histogram],
    gaugesCache: ConcurrentHashMap[String, Gauge],
    countersCache: ConcurrentHashMap[String, Counter],
    summariesCache: ConcurrentHashMap[String, Summary]
) extends RequestListener[Identity, RequestCollectors] {

  override def beforeRequest(request: GenericRequest[_, _]): RequestCollectors = {
    val requestTimer: Option[Histogram.Timer] = for {
      histogramData <- requestToHistogramNameMapper(request)
      histogram: Histogram = getOrCreateMetric(histogramsCache, histogramData, createNewHistogram)
    } yield histogram.labels(histogramData.labelValues: _*).startTimer()

    val gauge: Option[Gauge.Child] = for {
      gaugeData <- requestToInProgressGaugeNameMapper(request)
    } yield getOrCreateMetric(gaugesCache, gaugeData, createNewGauge).labels(gaugeData.labelValues: _*)

    observeRequestContentLengthSummaryIfMapped(request, requestToSizeSummaryMapper)

    gauge.foreach(_.inc())

    RequestCollectors(requestTimer, gauge)
  }

  override def requestException(
      request: GenericRequest[_, _],
      requestCollectors: RequestCollectors,
      e: Exception
  ): Unit =
    HttpError.find(e) match {
      case Some(HttpError(body, statusCode)) =>
        requestSuccessful(request, Response(body, statusCode, request.onlyMetadata), requestCollectors)
      case _ =>
        requestCollectors.maybeTimer.foreach(_.observeDuration())
        requestCollectors.maybeGauge.foreach(_.dec())
        incCounterIfMapped((request, e), requestToFailureCounterMapper)
    }

  override def requestSuccessful(
      request: GenericRequest[_, _],
      response: Response[_],
      requestCollectors: RequestCollectors
  ): Unit = {
    requestCollectors.maybeTimer.foreach(_.observeDuration())
    requestCollectors.maybeGauge.foreach(_.dec())
    observeResponseContentLengthSummaryIfMapped(request, response, responseToSizeSummaryMapper)

    if (response.isSuccess) {
      incCounterIfMapped((request, response), requestToSuccessCounterMapper)
    } else {
      incCounterIfMapped((request, response), requestToErrorCounterMapper)
    }
  }

  private def incCounterIfMapped[T](
      request: T,
      mapper: T => Option[BaseCollectorConfig]
  ): Unit =
    mapper(request).foreach { data =>
      getOrCreateMetric(countersCache, data, createNewCounter).labels(data.labelValues: _*).inc()
    }

  private def observeResponseContentLengthSummaryIfMapped(
      request: GenericRequest[_, _],
      response: Response[_],
      mapper: ((GenericRequest[_, _], Response[_])) => Option[BaseCollectorConfig]
  ): Unit =
    mapper((request, response)).foreach { data =>
      response.contentLength.map(_.toDouble).foreach { size =>
        getOrCreateMetric(summariesCache, data, createNewSummary).labels(data.labelValues: _*).observe(size)
      }
    }

  private def observeRequestContentLengthSummaryIfMapped(
      request: GenericRequest[_, _],
      mapper: GenericRequest[_, _] => Option[BaseCollectorConfig]
  ): Unit =
    mapper(request).foreach { data =>
      (request.contentLength: Option[Long]).map(_.toDouble).foreach { size =>
        getOrCreateMetric(summariesCache, data, createNewSummary).labels(data.labelValues: _*).observe(size)
      }
    }

  private def getOrCreateMetric[T, C <: BaseCollectorConfig](
      cache: ConcurrentHashMap[String, T],
      data: C,
      create: C => T
  ): T =
    cache.computeIfAbsent(
      data.collectorName,
      new java.util.function.Function[String, T] {
        override def apply(t: String): T = create(data)
      }
    )

  private def createNewHistogram(data: HistogramCollectorConfig): Histogram =
    Histogram
      .build()
      .buckets(data.buckets: _*)
      .name(data.collectorName)
      .labelNames(data.labelNames: _*)
      .help(data.collectorName)
      .register(collectorRegistry)

  private def createNewGauge(data: BaseCollectorConfig): Gauge =
    Gauge
      .build()
      .name(data.collectorName)
      .labelNames(data.labelNames: _*)
      .help(data.collectorName)
      .register(collectorRegistry)

  private def createNewCounter(data: BaseCollectorConfig): Counter =
    Counter
      .build()
      .name(data.collectorName)
      .labelNames(data.labelNames: _*)
      .help(data.collectorName)
      .register(collectorRegistry)

  private def createNewSummary(data: BaseCollectorConfig): Summary =
    Summary
      .build()
      .name(data.collectorName)
      .labelNames(data.labelNames: _*)
      .help(data.collectorName)
      .register(collectorRegistry)
}

trait BaseCollectorConfig {
  type T <: BaseCollectorConfig

  def collectorName: String
  def labels: List[(String, String)]

  def addLabels(lbs: List[(String, String)]): T

  def labelNames: Seq[String] = labels.map(_._1)
  def labelValues: Seq[String] = labels.map(_._2)
}

/** Represents the name of a collector, together with label names and values. The same labels must be always returned,
  * and in the same order.
  */
case class CollectorConfig(collectorName: String, labels: List[(String, String)] = Nil) extends BaseCollectorConfig {
  override type T = CollectorConfig
  override def addLabels(lbs: List[(String, String)]): CollectorConfig = copy(labels = labels ++ lbs)
}

/** Represents the name of a collector with configurable histogram buckets. */
case class HistogramCollectorConfig(
    collectorName: String,
    labels: List[(String, String)] = Nil,
    buckets: List[Double] = HistogramCollectorConfig.DefaultBuckets
) extends BaseCollectorConfig {
  override type T = HistogramCollectorConfig
  override def addLabels(lbs: List[(String, String)]): HistogramCollectorConfig = copy(labels = labels ++ lbs)
}

object HistogramCollectorConfig {
  val DefaultBuckets: List[Double] = List(.005, .01, .025, .05, .075, .1, .25, .5, .75, 1, 2.5, 5, 7.5, 10)
}
