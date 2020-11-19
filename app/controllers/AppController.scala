package controllers

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import api.{ ExampleApi, ExampleData, ExampleService }
import api.ExampleService.ExampleService
import caliban.{ CalibanError, GraphQLInterpreter, PlayAdapter }
import javax.inject._
import play.api.mvc._
import zio.{ Runtime, ZEnv, ZLayer }

@Singleton
class AppController @Inject()(val controllerComponents: ControllerComponents) extends BaseController {

  val calibanPlayAdapter: PlayAdapter[ZEnv] =
    PlayAdapter[ZEnv](controllerComponents.parsers, controllerComponents.actionBuilder)

  implicit val runtime: Runtime[ZEnv] = Runtime.default
  implicit val system                 = ActorSystem("play-example")
  implicit val ec                     = system.dispatcher

  val service: ZLayer[Any, Nothing, ExampleService] = ExampleService.make(ExampleData.sampleCharacters)

  val interpreter: GraphQLInterpreter[ZEnv, CalibanError] = runtime.unsafeRun(
    ExampleService
      .make(ExampleData.sampleCharacters)
      .memoize
      .use(layer => ExampleApi.api.interpreter.map(_.provideCustomLayer(layer)))
  )

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  val graphqlBody = calibanPlayAdapter.makePostAction(interpreter)

  def graphql(query: Option[String], variables: Option[String], operation: Option[String], extensions: Option[String]) =
    calibanPlayAdapter.makeGetAction(interpreter)(query, variables, operation, extensions)

  def webSocketActionImpl: WebSocket = calibanPlayAdapter.makeWebSocket(interpreter)

}
