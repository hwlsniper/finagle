package com.twitter.finagle.factory

import com.twitter.finagle._
import com.twitter.util.{Future, Duration, Timer}

object TimeoutFactory {

  /**
   * A class eligible for configuring a [[com.twitter.finagle.Stackable]]
   * [[com.twitter.finagle.factory.TimeoutFactory]].
   */
  case class Param(timeout: Duration) {
    def mk(): (Param, Stack.Param[Param]) =
      (this, Param.param)
  }
  object Param {
    implicit val param = Stack.Param(Param(Duration.Top))
  }

  /**
   * Creates a [[com.twitter.finagle.Stackable]] [[com.twitter.finagle.factory.TimeoutFactory]].
   *
   * @param _role The stack role used to identify the TimeoutFactory when inserted
   * into a stack.
   */
  private[finagle] def module[Req, Rep](_role: Stack.Role): Stackable[ServiceFactory[Req, Rep]] =
    new Stack.Module3[Param, param.Timer, param.Label, ServiceFactory[Req, Rep]] {
      val role = _role
      val description = "Timeout service acquisition after a given period"
      def make(
        _timeout: Param,
        _timer: param.Timer,
        _label: param.Label,
        next: ServiceFactory[Req, Rep]
      ): TimeoutFactory[Req, Rep] = {
        val Param(timeout) = _timeout
        val param.Label(label) = _label
        val param.Timer(timer) = _timer

        val exc = new ServiceTimeoutException(timeout)
        exc.serviceName = label
        new TimeoutFactory(next, timeout, exc, timer)
      }
    }
}

/**
 * A factory wrapper that times out the service acquisition after the
 * given time.
 *
 * @see The [[https://twitter.github.io/finagle/guide/Servers.html#request-timeout user guide]]
 *      for more details.
 */
class TimeoutFactory[Req, Rep](
  self: ServiceFactory[Req, Rep],
  timeout: Duration,
  exception: ServiceTimeoutException,
  timer: Timer
) extends ServiceFactoryProxy[Req, Rep](self) {
  private[this] val failure = Future.exception(Failure.adapt(exception, FailureFlags.Retryable))

  override def apply(conn: ClientConnection): Future[Service[Req, Rep]] = {
    val res = super.apply(conn)
    res.within(timer, timeout).rescue {
      case exc: java.util.concurrent.TimeoutException =>
        res.raise(exc)
        res.onSuccess { _.close() }
        failure
    }
  }
}
