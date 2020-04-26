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

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class AppController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  val jsonBackend = new PlayJsonBackend()

  implicit val runtime: Runtime[ZEnv] = Runtime.default

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

}
