package com.gu.mediaservice.lib.auth.provider

import akka.actor.ActorSystem
import com.gu.mediaservice.lib.auth.provider.Authentication.Principal
import com.gu.mediaservice.lib.config.CommonConfig
import play.api.Configuration
import play.api.libs.ws.{WSClient, WSRequest}
import play.api.mvc.{ControllerComponents, RequestHeader, Result}

import scala.concurrent.{ExecutionContext, Future}

/**
  * Case class containing useful resources for authentication providers to allow concurrent processing and external
  * API calls to be conducted.
  * @param config the tree of configuration for this provider
  * @param commonConfig the Grid common config object
  * @param context an execution context
  * @param actorSystem an actor system
  * @param wsClient a play WSClient for making API calls
  */
case class AuthenticationProviderResources(config: Configuration,
                                           commonConfig: CommonConfig,
                                           context: ExecutionContext,
                                           actorSystem: ActorSystem,
                                           wsClient: WSClient,
                                           controllerComponents: ControllerComponents)

sealed trait AuthenticationProvider {
  def initialise(): Unit = {}
  def shutdown(): Future[Unit] = Future.successful(())

  /**
    * A function that allows downstream API calls to be made using the credentials of the inflight request
    * @param request The request header of the inflight call
    * @return A function that adds appropriate data to a WSRequest
    */
  def onBehalfOf(request: RequestHeader): WSRequest => Either[String, WSRequest]
}

trait UserAuthenticationProvider extends AuthenticationProvider {
  /**
    * Establish the authentication status of the given request header. This can return an authenticated user or a number
    * of reasons why a user is not authenticated.
    * @param request The request header containing cookies and other request headers that can be used to establish the
    *           authentication status of a request.
    * @return An authentication status expressing whether the
    */
  def authenticateRequest(request: RequestHeader): AuthenticationStatus

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide a function here to redirect the user. The function signature takes the applications callback URL as
    * well as the request and should return a result.
    */
  def sendForAuthentication: Option[(RequestHeader, Option[Principal]) => Future[Result]]

  /**
    * If this provider supports sending a user that is not authorised to a federated auth provider then it should
    * provide an Play action here that deals with the return of a user from a federated provider. This should be
    * used to set a cookie or similar to ensure that a subsequent call to authenticateRequest will succeed. If
    * authentication failed then this should return an appropriate 4xx result.
    */
  def processAuthentication: Option[RequestHeader => Future[Result]]

  /**
    * If this provider is able to clear user tokens (i.e. by clearing cookies) then it should provide a function to
    * do that here which will be used to log users out and also if the token is invalid.
    * @return
    */
  def flushToken: Option[RequestHeader => Result]
}

trait ApiAuthenticationProvider extends AuthenticationProvider {
  /**
    * Establish the authentication status of the given request header. This can return an authenticated user or a number
    * of reasons why a user is not authenticated.
    * @param request The request header containing cookies and other request headers that can be used to establish the
    *           authentication status of a request.
    * @return An authentication status expressing whether the
    */
  def authenticateRequest(request: RequestHeader): ApiAuthenticationStatus
}
