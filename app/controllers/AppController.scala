package controllers

import javax.inject._
import play.api._
import play.api.mvc._
import caliban.GraphQLRequest
import scala.concurrent.Future
import zio.ZIO
import zio.Runtime
import scala.concurrent.Promise
import zio.Exit
import caliban.GraphQLResponse
import caliban.Value.NullValue
import zio.console.Console
import zio.clock.Clock
import api.ExampleService
import api.ExampleService.ExampleService
import api.ExampleData
import api.ExampleApi
import zio.ZLayer
import zio.ZEnv
import caliban.GraphQLInterpreter
import caliban.CalibanError
import play.api.http.Writeable
import play.api.libs.json.Writes
import caliban.interop.play.PlayJsonBackend
import caliban.InputValue
import zio.CancelableFuture
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.SourceQueueWithComplete
import caliban.ResponseValue
import zio.Task
import zio.IO
import akka.stream.QueueOfferResult
import zio.Ref
import zio.Fiber
import zio.RIO
import caliban.ResponseValue.ObjectValue
import caliban.ResponseValue.StreamValue
import zio.duration.Duration
import akka.stream.OverflowStrategy
import zio.Schedule
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.ActorMaterializer
import scala.concurrent.ExecutionContext

//       "com.typesafe.akka" %% "akka-stream"         % "2.6.3",
//       "de.heikoseeberger" %% "akka-http-circe"     % "1.31.0" % Optional,
//       "de.heikoseeberger" %% "akka-http-play-json" % "1.31.0" % Optional,
//

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class AppController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  val jsonBackend                     = new PlayJsonBackend()
  implicit val runtime: Runtime[ZEnv] = Runtime.default
  implicit val system                 = ActorSystem("play-example")
  implicit val ec                     = system.dispatcher
  implicit val materializer           = ActorMaterializer()

  val service: ZLayer[Any, Nothing, ExampleService] = ExampleService.make(ExampleData.sampleCharacters)

  val interpreter: GraphQLInterpreter[ZEnv, CalibanError] = runtime.unsafeRun(
    ExampleService
      .make(ExampleData.sampleCharacters)
      .memoize
      .use(layer => ExampleApi.api.interpreter.map(_.provideCustomLayer(layer)))
  )

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index() = Action { implicit request: Request[AnyContent] => Ok(views.html.index()) }

  implicit def writableGraphQLResponse[E](implicit wr: Writes[GraphQLResponse[E]]): Writeable[GraphQLResponse[E]] =
    Writeable.writeableOf_JsValue.map(wr.writes)

  def completeRequest(
    request: GraphQLRequest
  ): CancelableFuture[Result] =
    runtime.unsafeRunToFuture(
      interpreter
        .execute(request.query.getOrElse(""), request.operationName, request.variables.getOrElse(Map.empty))
        .catchAllCause(cause => ZIO.succeed(GraphQLResponse[Throwable](NullValue, cause.defects)))
        .map(Ok(_))
    )

  val graphqlBody: Action[GraphQLRequest] =
    Action.async(parse.json[GraphQLRequest])(req => completeRequest(req.body))

  def graphql(query: String, variables: Option[String], operation: Option[String], extensions: Option[String]) =
    Action.async {
      jsonBackend
        .parseHttpRequest(
          query,
          variables,
          operation,
          extensions
        )
        .fold(
          th => Future.successful(BadRequest(th.getMessage())),
          completeRequest
        )
    }

  def socket() = WebSocket.accept[String, String] { request =>
    Flow[String].mapConcat {
      case "ping" => List("pong")
      case _      => List.empty
    }
  }

  def webSocketAction[R, E](
    interpreter: GraphQLInterpreter[R, E],
    skipValidation: Boolean = false,
    enableIntrospection: Boolean = true,
    keepAliveTime: Option[Duration] = None
  )(implicit ec: ExecutionContext, runtime: Runtime[R], materializer: Materializer):WebSocket = {
    def sendMessage[E](
      sendQueue: SourceQueueWithComplete[String],
      id: String,
      data: ResponseValue,
      errors: List[E]
    ): Task[QueueOfferResult] =
      IO.fromFuture(_ => sendQueue.offer(jsonBackend.encodeWSResponse(id, data, errors)))

    def startSubscription(
      messageId: String,
      request: GraphQLRequest,
      sendTo: SourceQueueWithComplete[String],
      subscriptions: Ref[Map[Option[String], Fiber[Throwable, Unit]]]
    ): RIO[R, Unit] =
      for {
        result <- interpreter.executeRequest(
                   request,
                   skipValidation = skipValidation,
                   enableIntrospection = enableIntrospection
                 )
        _ <- result.data match {
              case ObjectValue((fieldName, StreamValue(stream)) :: Nil) =>
                stream
                  .foreach(item => sendMessage(sendTo, messageId, ObjectValue(List(fieldName -> item)), result.errors))
                  .forkDaemon
                  .flatMap(fiber => subscriptions.update(_.updated(Option(messageId), fiber)))
              case other =>
                sendMessage(sendTo, messageId, other, result.errors) *>
                  IO.fromFuture(_ => sendTo.offer(s"""{"type":"complete","id":"$messageId"}"""))
            }
      } yield ()

    val (queue, source) = Source.queue[String](0, OverflowStrategy.fail).preMaterialize()
    val subscriptions   = runtime.unsafeRun(Ref.make(Map.empty[Option[String], Fiber[Throwable, Unit]]))

    val sink = Sink.foreach[String] { text =>
      val io = for {
        msg     <- Task.fromEither(jsonBackend.parseWSMessage(text))
        msgType = msg.messageType
        _ <- IO.whenCase(msgType) {
              case "connection_init" =>
                Task.fromFuture(_ => queue.offer("""{"type":"connection_ack"}""")) *>
                  Task.whenCase(keepAliveTime) {
                    case Some(time) =>
                      // Save the keep-alive fiber with a key of None so that it's interrupted later
                      IO.fromFuture(_ => queue.offer("""{"type":"ka"}"""))
                        .repeat(Schedule.spaced(time))
                        .provideLayer(Clock.live)
                        .unit
                        .forkDaemon
                        .flatMap(keepAliveFiber => subscriptions.update(_.updated(None, keepAliveFiber)))
                  }
              case "connection_terminate" =>
                IO.effect(queue.complete())
              case "start" =>
                Task.whenCase(msg.request) {
                  case Some(req) =>
                    startSubscription(msg.id, req, queue, subscriptions).catchAll(error =>
                      IO.fromFuture(_ => queue.offer(jsonBackend.encodeWSError(msg.id, error)))
                    )
                }
              case "stop" =>
                subscriptions
                  .modify(map => (map.get(Option(msg.id)), map - Option(msg.id)))
                  .flatMap(fiber =>
                    IO.whenCase(fiber) {
                      case Some(fiber) =>
                        fiber.interrupt *>
                          IO.fromFuture(_ => queue.offer(s"""{"type":"complete","id":"${msg.id}"}"""))
                    }
                  )
            }
      } yield ()
      runtime.unsafeRun(io)
    }


    WebSocket.accept[String, String] { request =>
    Flow.fromSinkAndSource(sink, source).watchTermination() { (_, f) =>
      f.onComplete(_ => runtime.unsafeRun(subscriptions.get.flatMap(m => IO.foreach(m.values)(_.interrupt).unit)))
    }
  }
}

  def webSocketActionImpl = webSocketAction(interpreter)

}
